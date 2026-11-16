"""Pinned Gitleaks scan of all Git refs and versionable working files; metadata only."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
import zipfile

VERSION = "8.30.1"
ARCHIVES = {
    "Linux": ("linux_x64.tar.gz", "551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb"),
    "Windows": ("windows_x64.zip", "d29144deff3a68aa93ced33dddf84b7fdc26070add4aa0f4513094c8332afc4e"),
}
FIELDS = ("RuleID", "File", "StartLine", "EndLine", "Commit")
EXCEPTIONS_FILE = '.gitleaks-history-exceptions.json'


def checked_path(value):
    """Confine CLI file access to the checkout or runner scratch space."""
    candidate = Path(value).resolve()
    roots = (Path.cwd().resolve(), Path(os.environ.get('RUNNER_TEMP') or tempfile.gettempdir()).resolve())
    if not any(candidate.is_relative_to(root) for root in roots):
        raise ValueError('Path is outside the checkout and runner temporary directory')
    return candidate


def valid_path(path):
    return (isinstance(path, str) and bool(path) and not PurePosixPath(path).is_absolute()
            and PurePosixPath(path).as_posix() == path and '..' not in PurePosixPath(path).parts
            and not any(char in path for char in '\\:*?[]{}\r\n\0'))


def valid_fingerprint(value):
    if not isinstance(value, str):
        return False
    parts = value.split(':')
    return (len(parts) == 4 and re.fullmatch(r'[0-9a-f]{40}', parts[0]) is not None
            and valid_path(parts[1]) and re.fullmatch(r'[a-z0-9][a-z0-9-]*', parts[2]) is not None
            and re.fullmatch(r'[1-9][0-9]*', parts[3]) is not None)


def load_exceptions(repo):
    path = repo / EXCEPTIONS_FILE
    if path.is_symlink():
        raise RuntimeError('Exception inventory must be a regular file')
    if not path.exists():
        return set()
    def unique_object(pairs):
        if len({key for key, value in pairs}) != len(pairs):
            raise RuntimeError('Duplicate exception inventory keys')
        return dict(pairs)
    policy = json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique_object)
    if (not isinstance(policy, dict) or set(policy) != {'version', 'fingerprints'}
            or type(policy['version']) is not int or policy['version'] != 1
            or not isinstance(policy['fingerprints'], list)
            or not all(valid_fingerprint(value) for value in policy['fingerprints'])):
        raise RuntimeError('Invalid historical exception inventory')
    fingerprints = set(policy['fingerprints'])
    if len(fingerprints) != len(policy['fingerprints']):
        raise RuntimeError('Duplicate historical exception')
    return fingerprints


def git(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args], stderr=subprocess.DEVNULL)


def install(directory):
    if platform.machine().lower() not in ("amd64", "x86_64") or platform.system() not in ARCHIVES:
        raise RuntimeError("Use --gitleaks with Gitleaks 8.30.1 on this platform")
    suffix, expected = ARCHIVES[platform.system()]
    url = f"https://github.com/gitleaks/gitleaks/releases/download/v{VERSION}/gitleaks_{VERSION}_{suffix}"
    with urllib.request.urlopen(url, timeout=60) as response:
        archive = response.read()
    if hashlib.sha256(archive).hexdigest() != expected:
        raise RuntimeError("Gitleaks archive checksum mismatch")
    name = "gitleaks.exe" if platform.system() == "Windows" else "gitleaks"
    if suffix.endswith("zip"):
        with zipfile.ZipFile(io.BytesIO(archive)) as source:
            content = source.read(name)
    else:
        with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as source:
            content = source.extractfile(name).read()
    executable = directory / name
    executable.write_bytes(content)
    executable.chmod(0o700)
    return str(executable)


def metadata(findings, staging=None):
    safe = []
    for finding in findings:
        item = {key: finding.get(key) for key in FIELDS}
        if staging:
            path = Path(item["File"])
            if path.is_absolute():
                item["File"] = path.relative_to(staging).as_posix()
        safe.append(item)
    return safe


def scan(repo, executable, temporary):
    if git(repo, "rev-parse", "--is-shallow-repository").strip() != b"false":
        raise RuntimeError("Full history required; use checkout fetch-depth: 0")
    config = repo / ".gitleaks.toml"
    if not config.is_file():
        raise RuntimeError("Missing repository Gitleaks configuration")
    # Gitleaks also loads source/.gitleaksignore despite an explicit override.
    # Keep rejecting it: approved exceptions are applied after the complete scan.
    if (repo / '.gitleaksignore').exists():
        raise RuntimeError('Use the strict historical exception inventory, not .gitleaksignore')
    approved = load_exceptions(repo)
    matched = set()
    # Git's inventory includes untracked, nonignored files without scanning runtime
    # .env, build caches, node_modules or other intentionally ignored local data.
    inventory = git(repo, "ls-files", "-z", "--cached", "--others", "--exclude-standard").split(b"\0")
    staging = temporary / "working-tree"
    staging.mkdir()
    count = 0
    for raw in sorted(set(inventory) - {b""}):
        relative = Path(os.fsdecode(raw))
        source = repo / relative
        if source.is_symlink() or not source.resolve().is_relative_to(repo):
            raise RuntimeError("Review symlink before scanning the working tree")
        if source.is_file():
            target = staging / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            count += 1
    result = {"version": VERSION, "commit": git(repo, "rev-parse", "HEAD").decode().strip(),
              "commits": int(git(repo, "rev-list", "--all", "--count")), "working_files": count}
    # No external ignore entries either. Source-level ignores are rejected above.
    no_ignores = temporary / 'no-ignores'
    no_ignores.write_text('', encoding='utf-8')
    for mode, target in (("history", repo), ("working_tree", staging)):
        report = temporary / (mode + ".json")
        # Relative file paths let fixture allowlists use ^exact/path$ anchors.
        command = [executable, "git" if mode == "history" else "dir", str(target) if mode == 'history' else '.',
                   "--config=" + str(config), "--redact=100", "--no-banner", "--no-color",
                   "--ignore-gitleaks-allow", "--gitleaks-ignore-path=" + str(no_ignores),
                   "--log-level=error", "--report-format=json", "--report-path=" + str(report), "--timeout=180"]
        if mode == "history":
            command.append("--log-opts=--all --full-history -m")
        # Never forward scanner stdout/stderr or upload its raw report: other
        # literals on a matched line may survive even when Secret is redacted.
        completed = subprocess.run(command, cwd=repo if mode == 'history' else staging,
                                   capture_output=True, timeout=195)
        if completed.returncode not in (0, 1) or completed.stderr.strip() or not report.exists():
            raise RuntimeError("Gitleaks scan failed; no clean result is asserted")
        raw = json.loads(report.read_text(encoding='utf-8-sig'))
        if not isinstance(raw, list) or completed.returncode != int(bool(raw)):
            raise RuntimeError('Inconsistent scanner result')
        safe = metadata(raw, staging if mode == 'working_tree' else None)
        result[mode] = []
        if mode == 'history':
            result['history_findings_total'] = len(raw)
        for finding, item in zip(raw, safe):
            if (not valid_path(item['File']) or not isinstance(item['RuleID'], str)
                    or re.fullmatch(r'[a-z0-9][a-z0-9-]*', item['RuleID']) is None
                    or type(item['StartLine']) is not int or item['StartLine'] < 1
                    or type(item['EndLine']) is not int or item['EndLine'] < item['StartLine']):
                raise RuntimeError('Invalid scanner finding')
            if mode == 'history':
                fingerprint = f"{item['Commit']}:{item['File']}:{item['RuleID']}:{item['StartLine']}"
                if not valid_fingerprint(fingerprint) or finding.get('Fingerprint') != fingerprint:
                    raise RuntimeError('Invalid historical scanner fingerprint')
                if fingerprint in approved:
                    matched.add(fingerprint)
                    continue
            elif item['Commit'] != '':
                raise RuntimeError('Unexpected working-tree commit')
            result[mode].append(item)
    if matched != approved:
        raise RuntimeError('Stale or unmatched historical exception; review full history and inventory')
    result['historical_exceptions'] = {'approved': len(approved), 'matched': len(matched)}
    result["status"] = "findings" if result["history"] or result["working_tree"] else "clean"
    return result


def self_test(executable):
    """Fail closed on real detector regressions; never forward fixture output."""
    suite = Path(__file__).resolve().parents[1] / 'tests/test_secret_detection.py'
    env = {**os.environ, 'M2_GITLEAKS_TEST_EXECUTABLE': executable, 'PYTHONDONTWRITEBYTECODE': '1'}
    tested = subprocess.run([sys.executable, '-B', str(suite)], env=env,
                            capture_output=True, timeout=120)
    if tested.returncode:
        raise RuntimeError('Detector regressions failed (output suppressed)')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--gitleaks", help="Existing Gitleaks 8.30.1 executable; otherwise download verified official binary")
    parser.add_argument("--output", type=checked_path, help="Optional metadata-only JSON, never raw scanner output")
    parser.add_argument("--self-test", action="store_true", help="Require real detector regressions before scanning (mandatory in CI)")
    args = parser.parse_args()
    started = time.monotonic()
    try:
        with tempfile.TemporaryDirectory(prefix="m2-secrets-") as name:
            temporary = Path(name)
            executable = str(Path(args.gitleaks).resolve()) if args.gitleaks else install(temporary)
            version = subprocess.check_output([executable, "version"], stderr=subprocess.DEVNULL, timeout=10).decode().strip()
            if version != VERSION:
                raise RuntimeError("Gitleaks 8.30.1 required")
            if args.self_test:
                self_test(executable)
            result = scan(args.repo.resolve(), executable, temporary)
            if args.self_test:
                result['detector_regressions'] = 'passed'
        result["seconds"] = round(time.monotonic() - started, 2)
    except Exception:
        # Deliberately omit exception strings; subprocess errors can include data.
        result = {"version": VERSION, "status": "error", "message": "Secret scan incomplete; check full history, configuration, tool and network availability"}
    safe = json.dumps(result, ensure_ascii=True, indent=2) + "\n"
    if args.output:
        args.output.write_text(safe, encoding="utf-8")
    print(safe, end="")
    return 0 if result["status"] == "clean" else 1


if __name__ == "__main__":
    raise SystemExit(main())

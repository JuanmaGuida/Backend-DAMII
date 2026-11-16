"""Trivy reports for QA. No exceptions: every HIGH/CRITICAL blocks CI."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

VERSION = '0.76.2'
FIELDS = ('VulnerabilityID', 'PkgName', 'InstalledVersion', 'FixedVersion', 'Severity', 'Status', 'PkgID')


def summarize(raw):
    results = raw.get('Results', [])
    if not isinstance(results, list):
        raise ValueError('Invalid scan results')
    packages = sum(len(result.get('Packages', [])) for result in results)
    if packages == 0:
        raise ValueError('No package inventory detected; scan cannot establish coverage')
    findings = []
    for result in results:
        for vuln in result.get('Vulnerabilities', []):
            findings.append({**{key: vuln.get(key, '') for key in FIELDS},
                             'Type': result.get('Type', ''), 'Target': result.get('Target', '')})
    return {'packages': packages, 'findings': findings,
            'blocking': sum(v['Severity'] in ('HIGH', 'CRITICAL', 'UNKNOWN', '') for v in findings)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--fs', action='append', default=[])
    parser.add_argument('--rootfs', action='append', default=[], help='Binary/JAR inventory (Go providers, Terraform CLI)')
    parser.add_argument('--image', action='append', default=[])
    parser.add_argument('--archive', action='append', default=[])
    parser.add_argument('--npm', help='Directory containing package-lock.json')
    parser.add_argument('--output', required=True)
    parser.add_argument('--trivy', default='trivy')
    args = parser.parse_args()
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    report = {'scanner': 'Trivy', 'version': VERSION, 'revision': os.environ.get('GITHUB_SHA', ''),
              'generatedAt': datetime.now(timezone.utc).isoformat(), 'policy': 'Block HIGH/CRITICAL/UNKNOWN; no suppressions',
              'scans': [], 'errors': []}
    if args.npm:
        try:
            audit = subprocess.run(['npm.cmd' if os.name == 'nt' else 'npm', 'audit', '--json', '--package-lock-only'],
                                   cwd=args.npm, capture_output=True, text=True, timeout=120)
            data = json.loads(audit.stdout)
            if audit.returncode not in (0, 1) or data.get('error') or not data.get('metadata', {}).get('dependencies', {}).get('total'):
                raise ValueError('npm audit failed')
            findings = []
            for name, item in data.get('vulnerabilities', {}).items():
                findings.append({'PkgName': name, 'Severity': item['severity'].upper(),
                                 'Direct': item['isDirect'], 'Range': item['range'],
                                 'Via': [v if isinstance(v, str) else {'Name': v['name'], 'Source': v['source'], 'Range': v['range']}
                                         for v in item['via']], 'FixAvailable': item['fixAvailable']})
            report['scans'].append({'kind': 'npm-audit', 'target': args.npm, 'findings': findings,
                                    'packages': data['metadata']['dependencies']['total'],
                                    'blocking': sum(v['Severity'] in ('HIGH', 'CRITICAL', 'UNKNOWN', '') for v in findings)})
        except Exception:
            report['errors'].append('npm audit failed; no valid dependency audit received')
    try:
        version = subprocess.run([args.trivy, '--version', '--format', 'json'], check=True,
                                 capture_output=True, text=True, timeout=30)
        metadata = json.loads(version.stdout)
        if metadata['Version'] != VERSION:
            raise ValueError('Unexpected Trivy version')
        with tempfile.TemporaryDirectory(prefix='m2-sca-') as directory:
            temporary = Path(directory)
            (temporary / 'config.yaml').write_text('{}\n')
            (temporary / 'ignore').write_text('')
            targets = [('fs', path) for path in args.fs] + [('rootfs', path) for path in args.rootfs] + [('image', path) for path in args.image] + [('archive', path) for path in args.archive]
            if not targets:
                raise ValueError('No scan targets')
            for index, (kind, target) in enumerate(targets):
                raw_path = temporary / f'{index}.json'
                command = [args.trivy, kind if kind in ('fs', 'rootfs') else 'image',
                           '--config', str(temporary / 'config.yaml'), '--ignorefile', str(temporary / 'ignore'),
                           '--scanners', 'vuln', '--format', 'json', '--list-all-pkgs',
                           '--exit-code', '0', '--timeout', '5m', '--output', str(raw_path)]
                if kind == 'fs':
                    command += ['--include-dev-deps', '--skip-dirs', '**/node_modules', '--skip-dirs', '**/.git',
                                '--skip-dirs', '**/coverage', '--skip-dirs', '**/dist', '--skip-dirs', '**/target']
                elif kind == 'archive':
                    command += ['--input', target]
                elif kind == 'image':
                    command += ['--image-src', 'remote', '--platform', 'linux/amd64']
                if kind != 'archive':
                    command.append(target)
                try:
                    subprocess.run(command, check=True, capture_output=True, timeout=330)
                    raw = json.loads(raw_path.read_text(encoding='utf-8'))
                    scan = summarize(raw)
                    if raw.get('Metadata', {}).get('OS', {}).get('EOSL'):
                        raise ValueError('Unsupported/end-of-life image OS')
                    # Do not export image config, environment, layers, source snippets or raw logs.
                    scan.update(kind=kind, target=target, imageID=raw.get('Metadata', {}).get('ImageID', ''),
                                repoDigests=raw.get('Metadata', {}).get('RepoDigests', []))
                    report['scans'].append(scan)
                except Exception:
                    report['errors'].append(f'Scan {index + 1} ({kind}) failed or returned no inventory')
            version = subprocess.run([args.trivy, '--version', '--format', 'json'], check=True,
                                     capture_output=True, text=True, timeout=30)
            report['databases'] = {key: value for key, value in json.loads(version.stdout).items()
                                   if key in ('VulnerabilityDB', 'JavaDB')}
    except Exception:
        report['errors'].append('Scanner setup/version failed')
    report['elapsedSeconds'] = round(time.monotonic() - started, 2)
    blocking = sum(scan['blocking'] for scan in report['scans'])
    failed = bool(report['errors'] or blocking)
    report.update(blocking=blocking, status='FAIL' if failed else 'PASS')
    (output / 'vulnerabilities.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    lines = ['## SEG-08', '', f"Trivy {VERSION}: {report['status']}; HIGH/CRITICAL/UNKNOWN occurrences: {blocking}.",
             f"Scans completed: {len(report['scans'])}; errors: {len(report['errors'])}; seconds: {report['elapsedSeconds']}.",
             'See vulnerabilities.json for package versions, fixes and image digests. No findings have been waived.', '']
    (output / 'summary.md').write_text('\n'.join(lines), encoding='utf-8')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as summary:
            summary.write('\n'.join(lines))
    print(lines[2])
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())

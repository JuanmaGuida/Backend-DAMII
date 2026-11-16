"""Failure propagation and evidence boundaries for TST-04 and SEG-08."""
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
FLOW = (ROOT / '.github/workflows/ci.yml').read_text(encoding='utf-8')


def module(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), ROOT / '.github/scripts' / (name + '.py'))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


SCA = module('scan-vulnerabilities')
SONAR = module('check-sonar') if (ROOT / '.github/scripts/check-sonar.py').exists() else None


class PathBoundaryTests(unittest.TestCase):
    def test_cli_paths_reject_traversal_and_symlink_escape_before_file_access(self):
        scanners = (SCA, SONAR, module('check-secrets'))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            checkout, scratch, outside = root / 'checkout', root / 'scratch', root / 'outside'
            for path in (checkout, scratch, outside):
                path.mkdir()
            with patch.object(Path, 'cwd', return_value=checkout), \
                 patch.dict(os.environ, {'RUNNER_TEMP': str(scratch)}):
                for scanner in scanners:
                    with self.subTest(scanner=scanner.__name__):
                        self.assertEqual(scanner.checked_path(scratch / 'report.json'), scratch / 'report.json')
                        self.assertEqual(scanner.checked_path(checkout / 'task.txt'), checkout / 'task.txt')
                        for target in (outside / 'report.json', checkout / '..' / 'outside' / 'report.json'):
                            with self.assertRaises(ValueError):
                                scanner.checked_path(target)
                try:
                    (scratch / 'linked').symlink_to(outside, target_is_directory=True)
                except OSError:
                    return  # Windows may require privilege; Linux CI exercises this branch.
                for scanner in scanners:
                    with self.assertRaises(ValueError):
                        scanner.checked_path(scratch / 'linked' / 'report.json')
            self.assertEqual(list(outside.iterdir()), [])


class SCATests(unittest.TestCase):
    def test_inventory_is_required_even_when_no_vulnerabilities_are_reported(self):
        for raw in ({}, {'Results': []}, {'Results': [{'Packages': []}]}):
            with self.assertRaises(ValueError):
                SCA.summarize(raw)

    def test_high_unfixed_and_unknown_findings_block_and_raw_details_are_removed(self):
        raw = {'Results': [{'Type': 'jar', 'Target': 'Java', 'Packages': [{}], 'Vulnerabilities': [
            {'VulnerabilityID': 'TEST-1', 'Severity': 'HIGH', 'FixedVersion': '', 'Secret': 'DO_NOT_EXPORT'},
            {'VulnerabilityID': 'TEST-2', 'Severity': 'UNKNOWN', 'Description': 'DO_NOT_EXPORT'},
            {'VulnerabilityID': 'TEST-3', 'Severity': 'MEDIUM'}]}]}
        result = SCA.summarize(raw)
        self.assertEqual(result['blocking'], 2)
        self.assertNotIn('DO_NOT_EXPORT', json.dumps(result))

    def run_scanner(self, behavior):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'safe'
            def fake(command, **kwargs):
                if '--version' in command:
                    return subprocess.CompletedProcess(command, 0, json.dumps({'Version': SCA.VERSION}), '')
                self.assertEqual(command[1], 'fs')
                self.assertIn('vuln', command)
                self.assertNotIn('--ignore-unfixed', command)
                self.assertEqual(Path(command[command.index('--ignorefile') + 1]).read_text(), '')
                if behavior == 'tool-error':
                    raise subprocess.CalledProcessError(2, command, stderr='DO_NOT_EXPORT')
                raw = {'Results': [{'Packages': [{}], 'Vulnerabilities': []}],
                       'Metadata': {'ImageConfig': {'Env': ['DO_NOT_EXPORT']}}}
                if behavior == 'high':
                    raw['Results'][0]['Vulnerabilities'] = [{'VulnerabilityID': 'TEST', 'Severity': 'HIGH'}]
                if behavior == 'empty':
                    raw['Results'] = []
                Path(command[command.index('--output') + 1]).write_text(json.dumps(raw))
                return subprocess.CompletedProcess(command, 0, '', '')
            with patch.object(sys, 'argv', ['scan', '--fs', '.', '--output', str(output)]), \
                 patch.object(SCA.subprocess, 'run', side_effect=fake), patch.dict(os.environ, {}, clear=True):
                status = SCA.main()
            report = json.loads((output / 'vulnerabilities.json').read_text())
            self.assertNotIn('DO_NOT_EXPORT', json.dumps(report))
            self.assertEqual(report['status'], 'PASS' if status == 0 else 'FAIL')
            return status

    def test_cli_exit_status_and_report_survive_tool_errors_empty_inventory_and_high_findings(self):
        self.assertEqual(self.run_scanner('clean'), 0)
        for case in ('tool-error', 'empty', 'high'):
            with self.subTest(case=case):
                self.assertEqual(self.run_scanner(case), 1)


@unittest.skipIf(SONAR is None, 'Infra has no application Sonar project')
class SonarTests(unittest.TestCase):
    def good_metrics(self):
        return {'ncloc': 100, 'bugs': 0, 'vulnerabilities': 0, 'duplicated_lines_density': 3,
                'reliability_rating': 1, 'security_rating': 1, 'security_hotspots': 0, 'line_coverage': 85}

    def test_overall_thresholds_missing_metrics_and_unreviewed_hotspots_fail(self):
        values = self.good_metrics()
        self.assertEqual(SONAR.overall_errors(values), [])
        for key, value in [('bugs', 1), ('vulnerabilities', 1), ('duplicated_lines_density', 3.01),
                           ('reliability_rating', 2), ('security_rating', 2), ('line_coverage', 84.99), ('ncloc', 0),
                           ('security_hotspots', 1), ('duplicated_lines_density', float('nan'))]:
            with self.subTest(metric=key):
                self.assertTrue(SONAR.overall_errors({**values, key: value}))
        self.assertTrue(SONAR.overall_errors({k: v for k, v in values.items() if k != 'vulnerabilities'}))
        self.assertTrue(SONAR.overall_errors({**values, 'security_hotspots': 1, 'security_hotspots_reviewed': 99.9}))
        self.assertEqual(SONAR.overall_errors({**values, 'security_hotspots': 1, 'security_hotspots_reviewed': 100}), [])

    def verify(self, stale=False, gate='OK', pr='', race=False, branch_type='LONG', is_main=True, existing=False):
        with tempfile.TemporaryDirectory() as directory:
            task = Path(directory) / 'report-task.txt'
            task.write_text('serverUrl=https://sonarcloud.io\nprojectKey=project\nceTaskId=task\n')
            calls = []
            def api(path, **query):
                calls.append(path)
                if path == 'ce/task':
                    return {'task': {'status': 'SUCCESS', 'componentKey': 'project', 'analysisId': 'analysis'}}
                if path == 'qualitygates/project_status':
                    self.assertEqual(query, {'analysisId': 'analysis'})
                    return {'projectStatus': {'status': gate}}
                if path == 'project_analyses/search':
                    revision = 'old' if stale or (race and calls.count(path) > 1) else 'revision'
                    return {'analyses': [{'key': 'analysis', 'revision': revision}]}
                if path == 'project_branches/list':
                    return {'branches': [{'name': 'master', 'type': branch_type, 'isMain': is_main}]}
                if path == 'measures/component':
                    return {'component': {'measures': [{'metric': k, 'value': str(v)} for k, v in self.good_metrics().items()]}}
                raise AssertionError('Unexpected endpoint')
            with patch.object(SONAR, 'api', side_effect=api):
                result = SONAR.verify_existing('project', 'master', 'revision') if existing else SONAR.verify(task, 'project', 'master', 'revision', pr)
            if existing:
                self.assertNotIn('ce/task', calls)
            if pr:
                self.assertNotIn('measures/component', calls)
            return result

    def test_only_matching_completed_analysis_can_certify_overall_code(self):
        self.assertTrue(self.verify()['overallVerified'])
        self.assertTrue(self.verify(gate='ERROR')['errors'])
        for options in ({'stale': True}, {'race': True}, {'branch_type': 'SHORT'}, {'is_main': False}):
            with self.assertRaises(ValueError):
                self.verify(**options)

    def test_pr_gate_does_not_claim_overall_or_release_compliance(self):
        result = self.verify(pr='42')
        self.assertFalse(result['overallVerified'])
        self.assertEqual(result['scope'], 'pull-request-new-code')

    def test_existing_main_evidence_never_accepts_another_revision_or_failed_gate(self):
        result = self.verify(existing=True)
        self.assertTrue(result['overallVerified'])
        self.assertEqual(result['evidenceMode'], 'existing-main-analysis')
        self.assertTrue(self.verify(existing=True, gate='ERROR')['errors'])
        for options in ({'stale': True}, {'race': True}, {'is_main': False}):
            with self.assertRaises(ValueError):
                self.verify(existing=True, **options)

    def test_free_scope_never_scans_dev_or_release_branches(self):
        for main in ('main', 'master'):
            for event, ref, base, expected in (
                ('push', 'refs/heads/' + main, '', 'branch'),
                ('workflow_dispatch', 'refs/heads/' + main, '', 'branch'),
                ('pull_request', 'refs/pull/1/merge', main, 'pull_request'),
                ('pull_request', 'refs/pull/2/merge', 'dev', 'unsupported'),
                ('push', 'refs/heads/dev', '', 'unsupported'),
                ('workflow_dispatch', 'refs/heads/dev', '', 'unsupported'),
                ('push', 'refs/tags/v2.0-entrega4', '', 'existing'),
                ('workflow_dispatch', 'refs/tags/v2.0-entrega4', '', 'existing'),
                ('workflow_dispatch', 'refs/heads/feature', '', 'unsupported')):
                with self.subTest(main=main, event=event, ref=ref):
                    self.assertEqual(SONAR.analysis_mode(event, ref, base, main), expected)

    def test_scope_without_analysis_is_visible_and_does_not_claim_a_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            output, summary = Path(directory) / 'output', Path(directory) / 'summary'
            env = {'GITHUB_EVENT_NAME': 'pull_request', 'GITHUB_REF': 'refs/pull/2/merge',
                   'GITHUB_BASE_REF': 'dev', 'GITHUB_OUTPUT': str(output), 'GITHUB_STEP_SUMMARY': str(summary)}
            with patch.object(sys, 'argv', ['check', '--scope', '--main-branch', 'master']), \
                 patch.dict(os.environ, env, clear=True), patch.object(SONAR, 'api') as api:
                self.assertEqual(SONAR.main(), 0)
                api.assert_not_called()
            self.assertIn('sonar_mode=unsupported', output.read_text())
            self.assertIn('NOT ANALYZED', summary.read_text())
            self.assertNotIn('PASS', summary.read_text())

    def test_dev_validation_does_not_require_an_impossible_main_sha(self):
        with tempfile.TemporaryDirectory() as directory:
            summary = Path(directory) / 'summary'
            env = {'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': 'refs/heads/dev',
                   'GITHUB_STEP_SUMMARY': str(summary)}
            with patch.object(sys, 'argv', ['check', '--scope', '--main-branch', 'master']), \
                 patch.dict(os.environ, env, clear=True), patch.object(SONAR, 'api') as api:
                self.assertEqual(SONAR.main(), 0)
                api.assert_not_called()
            text = summary.read_text()
            self.assertIn('validation-only', text)
            self.assertIn('Tests, coverage and SCA still run', text)
            self.assertIn('Publication and deployment are blocked', text)

    def test_existing_sha_outside_git_main_fails_before_any_service_request(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'evidence.json'
            env = {'SONAR_TOKEN': 'DO_NOT_EXPORT', 'SONAR_PROJECT_KEY': 'project',
                   'SONAR_ORGANIZATION': 'org', 'GITHUB_SHA': 'a' * 40, 'SONAR_BRANCH': 'master'}
            with patch.object(sys, 'argv', ['check', '--existing-analysis', '--output', str(output)]), \
                 patch.dict(os.environ, env, clear=True), patch.object(SONAR, 'api') as api, \
                 patch.object(SONAR.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1)):
                self.assertEqual(SONAR.main(), 1)
                api.assert_not_called()
            self.assertIn('not reachable', output.read_text())
            self.assertNotIn('DO_NOT_EXPORT', output.read_text())

    def test_missing_configuration_exits_nonzero_without_network_or_secrets_in_output(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'evidence.json'
            with patch.object(sys, 'argv', ['check', '--task', 'missing', '--output', str(output)]), \
                 patch.dict(os.environ, {'SONAR_TOKEN': 'DO_NOT_EXPORT'}, clear=True), patch.object(SONAR, 'api') as api:
                self.assertEqual(SONAR.main(), 1)
                api.assert_not_called()
            report = output.read_text()
            self.assertNotIn('DO_NOT_EXPORT', report)
            self.assertIn('NOT_VERIFIED', report)


class WorkflowTests(unittest.TestCase):
    @unittest.skipIf(SONAR is None, 'Infra does not publish application images')
    def test_publication_loads_scanned_image_and_stops_before_push_if_load_fails(self):
        frontend = 'Frontend CI' in FLOW
        title = 'Publish scanned Frontend image' if frontend else 'Publish scanned Backend image'
        block = FLOW.split('      - name: ' + title + '\n', 1)[1].split('\n      - ', 1)[0]
        script = block.split('        run: |\n', 1)[1]
        script = '\n'.join(line[10:] for line in script.splitlines() if line.startswith('          '))
        bash = r'C:\Program Files\Git\bin\bash.exe' if os.name == 'nt' else shutil.which('bash')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            runner = path / 'publish.sh'
            stub = '''set -euo pipefail
docker() {
  printf '%s\\n' "$*" >> "$CALLS"
  if [[ "$1" == load && "$FAIL_LOAD" == true ]]; then return 9; fi
  if [[ "$1" == inspect ]]; then printf '%s@sha256:%s\\n' "$IMAGE_REPOSITORY" "$TEST_DIGEST"; fi
}
'''
            runner.write_text(stub + script + '\n', encoding='utf-8')
            log = path / 'calls'
            output = path / 'output'
            environment = {**os.environ, 'GITHUB_SHA': 'a' * 40, 'RUNNER_TEMP': directory,
                           'CALLS': str(log), 'GITHUB_OUTPUT': str(output), 'FAIL_LOAD': 'false',
                           'IMAGE_REPOSITORY': 'ghcr.io/test/frontend', 'TEST_DIGEST': 'c' * 64,
                           'IMAGE_REFERENCE': 'ghcr.io/test/frontend:m1-sha-test',
                           'IMAGE_TAGS': 'ghcr.io/test/frontend:m1-sha-test'}
            result = subprocess.run([bash, str(runner)], env=environment, capture_output=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('load --input', log.read_text())
            self.assertIn('push ', log.read_text())
            self.assertNotIn('build ', log.read_text())
            if frontend:
                self.assertIn('digest=sha256:' + 'c' * 64, output.read_text())
                self.assertNotIn(':dev', log.read_text())
            log.unlink()
            environment['FAIL_LOAD'] = 'true'
            result = subprocess.run([bash, str(runner)], env=environment, capture_output=True, timeout=10)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn('push ', log.read_text())

    def test_all_scans_are_mandatory_and_always_upload_safe_reports(self):
        self.assertIn('scan-vulnerabilities.py', FLOW)
        self.assertIn('if: always()', FLOW)
        self.assertNotIn('continue-on-error', FLOW)
        if SONAR:
            security = re.search(r'^  security:\n(.*?)(?=(?:^  [\w-]+:)|\Z)', FLOW, re.M | re.S).group(1)
            self.assertNotRegex(security, r'(?m)^    if:')
            self.assertIn("if: needs.configuration.outputs.sonar_mode != 'unsupported'", FLOW)
            self.assertIn('--scope --main-branch', FLOW)
            self.assertIn('--existing-analysis', FLOW)
            self.assertIn('sonar.qualitygate.wait=true', FLOW)
            self.assertNotIn("format('release/{0}', github.ref_name)", FLOW)
            self.assertFalse((ROOT / '.github/workflows/build.yml').exists())
            self.assertIn('check-sonar.py --task', FLOW)
            publish = FLOW.split('  publish:', 1)[1].split('  deploy:', 1)[0]
            needs = re.search(r'needs: \[(.*?)\]', publish).group(1).split(', ')
            self.assertTrue({'sonar', 'security', 'secrets', 'validate'} <= set(needs))
            self.assertNotIn('build-push-action', publish)
            self.assertIn('docker load --input', publish)
            self.assertIn('docker build --pull', FLOW)
            kind = 'frontend' if 'Frontend CI' in FLOW else 'backend'
            self.assertIn('--target build', FLOW)
            self.assertIn(f'--archive "${{RUNNER_TEMP}}/image/{kind}-builder.tar"', FLOW)
            self.assertIn(f'--archive "${{RUNNER_TEMP}}/image/{kind}-image.tar"', FLOW)
        else:
            self.assertIn('--rootfs terraform/.terraform/providers', FLOW)
            self.assertIn('-lockfile=readonly', FLOW)
            self.assertIn('--image postgres:17 --image caddy:2.11.7-alpine', FLOW)


if __name__ == '__main__':
    unittest.main()

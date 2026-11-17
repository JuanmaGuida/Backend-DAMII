"""Real Gitleaks regressions; commits exist only in disposable local repositories.

The mandatory secrets job supplies its verified executable via --self-test.
Values are generated at runtime, never copied from historical credentials.
"""
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import tomllib
import unittest

ROOT = Path(__file__).resolve().parents[2]
EXECUTABLE = os.environ.get('M2_GITLEAKS_TEST_EXECUTABLE')
SPEC = importlib.util.spec_from_file_location('scanner_under_test', ROOT / '.github/scripts/check-secrets.py')
SCANNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCANNER)


@unittest.skipUnless(EXECUTABLE, 'Run check-secrets.py --self-test for real detector regressions')
class SecretDetectionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='m2-detector-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repo = self.root / 'repository'
        self.repo.mkdir()
        # Do not inherit GitHub checkout settings, signing, hooks or user config.
        self.env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
        self.env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull,
                        GIT_AUTHOR_NAME='Detector fixture', GIT_COMMITTER_NAME='Detector fixture',
                        GIT_AUTHOR_EMAIL='fixture@example.invalid', GIT_COMMITTER_EMAIL='fixture@example.invalid')
        self.git('init', '--quiet')
        self.git('config', 'core.hooksPath', str(self.root / 'no-hooks'))
        self.git('config', 'commit.gpgsign', 'false')
        shutil.copyfile(ROOT / '.gitleaks.toml', self.repo / '.gitleaks.toml')
        self.secret = secrets.token_hex(16)

    def git(self, *args):
        result = subprocess.run(['git', '-C', str(self.repo), *args], env=self.env,
                                capture_output=True, timeout=20)
        if result.returncode:
            self.fail('Disposable Git operation failed (output suppressed)')
        return result.stdout.decode().strip()

    def write(self, path, content):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding='utf-8')

    def commit(self):
        self.git('add', '--all')
        self.git('commit', '--quiet', '-m', 'Disposable detector fixture')
        return self.git('rev-parse', 'HEAD')

    def checked(self):
        with tempfile.TemporaryDirectory(prefix='runner-', dir=self.root) as temporary:
            return SCANNER.scan(self.repo.resolve(), EXECUTABLE, Path(temporary))

    def exceptions(self, findings):
        self.write(SCANNER.EXCEPTIONS_FILE, json.dumps({
            'version': 1, 'fingerprints': sorted({f['Fingerprint'] for f in findings})}) + '\n')

    def detect(self, ignore=None):
        report = self.root / 'report.json'
        if ignore is None:
            ignore = self.root / 'empty-ignore'
            ignore.write_text('', encoding='utf-8')
        result = subprocess.run([EXECUTABLE, 'git', str(self.repo),
                                 '--config=' + str(self.repo / '.gitleaks.toml'),
                                 '--ignore-gitleaks-allow', '--gitleaks-ignore-path=' + str(ignore),
                                 '--log-opts=--all --full-history -m', '--redact=100',
                                 '--no-banner', '--no-color', '--log-level=error',
                                 '--report-format=json', '--report-path=' + str(report)],
                                env=self.env, capture_output=True, timeout=30)
        self.assertIn(result.returncode, (0, 1), 'Detector failed (output suppressed)')
        self.assertFalse(result.stderr.strip(), 'Detector error (output suppressed)')
        self.assertTrue(report.exists())
        findings = json.loads(report.read_text(encoding='utf-8-sig'))
        safe = [{key: f.get(key) for key in (*SCANNER.FIELDS, 'Fingerprint')} for f in findings]
        self.assertEqual(result.returncode, int(bool(safe)))
        return safe

    def test_explicit_history_marker_is_not_a_credential_but_new_values_still_are(self):
        provider = 'src/main/java/com/reclamos/backend/identity/mock/MockIdentityProvider.java'
        self.write(provider, 'String ADMIN_PASSWORD = "<REDACTED>";\n'
                   + 'String AGENT_PASSWORD = "' + self.secret + '";\n')
        self.write('config/marker.env', 'APP_PASSWORD=${APP_PASSWORD:-<REDACTED>}\n'
                   + 'OTHER_PASSWORD=${OTHER_PASSWORD:-' + self.secret + '}\n')
        self.commit()
        found = self.detect()
        self.assertFalse(any(f['StartLine'] == 1 for f in found))
        self.assertTrue(any(f['File'] == provider and f['RuleID'] == 'm2-historical-mock-password'
                            and f['StartLine'] == 2 for f in found))
        self.assertTrue(any(f['File'] == 'config/marker.env' and f['RuleID'] == 'm2-credential-reference-default'
                            and f['StartLine'] == 2 for f in found))

    def test_reviewed_historical_fixture_exceptions_remain_exact(self):
        # Keep the generic rule's entropy threshold independent of random hex distribution.
        new_secret = self.secret + 'ZyXwVuTsRqPoNmLkJiHgFeDcBa9876543210'
        cases = [{'rule': 'm2-unquoted-config-credential', 'path': '.github/workflows/ci.yml', 'values': ['ci-qa-admin-password', 'ci-qa-agent-password', 'ci-qa-area-password', 'ci-qa-citizen-password', 'm2_qa_password']}, {'rule': 'm2-quoted-credential-assignment', 'path': 'src/test/java/com/reclamos/backend/security/LoginRateLimitSecurityTest.java', 'values': ['login-rate-limit-good-password']}, {'rule': 'm2-quoted-credential-assignment', 'path': 'src/test/java/com/reclamos/backend/security/LogHygieneIntegrationTest.java', 'values': ['log-hygiene-access-password-2c70', 'log-hygiene-password-7f3a9c']}, {'rule': 'generic-api-key', 'path': 'docs/security/seg07-evidence/baseline/report.json', 'values': ['5653a0ce1dc40f691526']}]
        for i, case in enumerate(cases):
            path = case['path']
            key = 'finding_key' if case['rule'] == 'generic-api-key' else 'password'
            quote = '' if case['rule'] == 'm2-unquoted-config-credential' else '"'
            lines = [key + '=' + quote + value + quote for value in case['values']]
            lines.append(key + '=' + quote + new_secret + quote)
            self.write(path, '\n'.join(lines) + '\n')
            self.write('other-' + str(i) + '.txt', key + '=' + quote + case['values'][0] + quote + '\n')
        self.commit()
        found = self.detect()
        for i, case in enumerate(cases):
            matching = [f for f in found if f['File'] == case['path'] and f['RuleID'] == case['rule']]
            self.assertEqual([f['StartLine'] for f in matching], [len(case['values']) + 1])
            self.assertTrue(any(f['File'] == 'other-' + str(i) + '.txt' and f['RuleID'] == case['rule'] for f in found))

    def test_password_formats_without_filename_coupling(self):
        samples = {
            'config/unrelated.env': 'POSTGRES_PASSWORD=' + self.secret,
            'config/.env.local': 'export DB_PASSWORD=' + self.secret,
            'config/unrelated.properties': 'spring.datasource.password = ' + self.secret,
            'config/unrelated.yaml': 'database:\n  password: ' + self.secret,
            'config/quoted.yml': 'password: "' + self.secret + '"',
            'config/unrelated.json': '{"password": "' + self.secret + '"}',
            'src/Unrelated.java': 'String password = "' + self.secret + '";',
            'src/unrelated.py': "client_secret = '" + self.secret + "'",
            'src/unrelated.js': 'const settings = {apiKey: "' + self.secret + '"};',
            'config/fallback.properties': 'db.password=${DB_PASSWORD:' + self.secret + '}',
            'config/fallback.env': 'DB_PASSWORD=${DB_PASSWORD:-' + self.secret + '}',
        }
        for path, content in samples.items():
            self.write(path, content + '\n')
        # Exercise both line endings independently of the host running CI.
        for ending, newline in (('lf', '\n'), ('crlf', '\r\n')):
            path = 'config/line-ending-' + ending + '.env'
            (self.repo / path).write_bytes(('POSTGRES_PASSWORD=' + self.secret + newline).encode())
            samples[path] = ''
        self.commit()
        detected = {f['File'] for f in self.detect() if f['RuleID'].startswith('m2-')}
        self.assertFalse(set(samples) - detected, 'Missing formats: ' + ', '.join(sorted(set(samples) - detected)))

    def test_exact_placeholders_references_and_reviewed_fixture(self):
        self.write('config/safe.env', 'DB_PASSWORD=${DATABASE_PASSWORD}\nAPI_KEY=$API_KEY\n'
                   'DB_PWD=%DATABASE_PASSWORD%\nPASSWORD=replace_with_local_password\n'
                   'CLIENT_SECRET=CHANGEME\nPASSWORD=REDACTED\n')
        self.write('config/safe.json', '{"password": "<local password>", "apiKey": "${API_KEY}"}\n')
        self.write('config/unicode.json', '{"password": "<contraseña del ejemplo>"}\n')
        self.write('config/required.yml', 'password: "${DB_PASSWORD:?set this locally}"\n')
        self.write('src/Reference.java', 'String value = "db.password=" + variable, "other=" + other;\n')
        fake_verifier_value = ''.join(('syn', 'thetic'))
        self.write('scripts/m1-lab-https.test.mjs', 'const env = {M1_TEST_CITIZEN1_PASSWORD: '
                   + repr(fake_verifier_value) + '};\n')
        self.commit()
        self.assertFalse(self.detect(), 'An explicit placeholder/reference/fixture was misclassified')
        # The fixture exception cannot cover another value in that file or the
        # same value elsewhere. No directory or test-file exemption is permitted.
        self.write('scripts/m1-lab-https.test.mjs', 'const env = {password: "' + self.secret + '"};\n')
        self.write('config/other.env', 'DB_PASSWORD=synthetic\n')
        unicode_secret = 'Contraseña' + self.secret
        self.write('config/new-unicode.json', '{"password": "' + unicode_secret + '"}\n')
        self.commit()
        detected = {f['File'] for f in self.detect()}
        expected = {'scripts/m1-lab-https.test.mjs', 'config/other.env', 'config/new-unicode.json'}
        self.assertFalse(expected - detected, 'Missing files: ' + ', '.join(sorted(expected - detected)))

    def test_fingerprint_exception_cannot_hide_new_commits_or_other_rules(self):
        self.write('README.md', 'Password: ' + self.secret + '\n')
        historical = self.commit()
        baseline = self.detect()
        self.assertTrue(baseline)
        for item in baseline:
            self.assertTrue(item['Fingerprint'].startswith(historical + ':README.md:'))
        self.exceptions(baseline)
        self.assertNotIn(self.secret, (self.repo / SCANNER.EXCEPTIONS_FILE).read_text())
        # Approval applies to the old commit only, never the current contents.
        result = self.checked()
        self.assertFalse(result['history'])
        self.assertTrue(result['working_tree'])
        self.assertEqual(result['status'], 'findings')
        self.write('README.md', 'Removed fixture.\n')
        self.commit()
        result = self.checked()
        self.assertEqual(result['status'], 'clean')
        self.assertEqual(result['historical_exceptions'], {'approved': len(baseline), 'matched': len(baseline)})
        # Identical content, same file and same line, but a new commit SHA.
        self.write('README.md', 'Password: ' + self.secret + '\n')
        formats = {
            'config/elsewhere.env': 'POSTGRES_PASSWORD=' + self.secret,
            'config/elsewhere.properties': 'db.password=' + self.secret,
            'config/elsewhere.yaml': 'password: ' + self.secret,
            'config/elsewhere.json': '{"password": "' + self.secret + '"}',
            'src/Elsewhere.java': 'String password = "' + self.secret + '";',
        }
        fresh_secret = secrets.token_hex(16)
        formats['config/new.env'] = 'POSTGRES_PASSWORD=' + fresh_secret
        for path, content in formats.items():
            self.write(path, content + '\n')
        token = 'gh' + 'p_' + secrets.token_hex(18)
        self.write('src/elsewhere.txt', token + '\n')
        self.write('keys/new.pem', '-----BEGIN ' + 'RSA PRIVATE KEY-----\n' + secrets.token_hex(40)
                   + '\n-----END ' + 'RSA PRIVATE KEY-----\n')
        current = self.commit()
        result = self.checked()
        found = result['history']
        expected = {'README.md', 'src/elsewhere.txt', 'keys/new.pem'} | set(formats)
        self.assertTrue(expected <= {f['File'] for f in found})
        self.assertTrue(all(f['Commit'] == current for f in found))
        self.assertIn('github-pat', {f['RuleID'] for f in found})
        self.assertIn('private-key', {f['RuleID'] for f in found})
        self.assertTrue(expected <= {f['File'] for f in result['working_tree']})
        self.assertEqual(result['status'], 'findings')
        self.assertTrue(all(f['StartLine'] == 1 for f in found if f['File'] == 'README.md'))
        for path in expected:
            self.write(path, 'Removed fixture.\n')
        self.commit()
        result = self.checked()
        self.assertEqual(result['status'], 'findings')
        self.assertTrue(result['history'])
        self.assertFalse(result['working_tree'])

    def test_history_scan_includes_other_local_refs(self):
        self.write('README.md', 'Clean fixture.\n')
        self.commit()
        original = self.git('symbolic-ref', '--short', 'HEAD')
        self.git('checkout', '--quiet', '-b', 'disposable-secret-branch')
        self.write('config/branch.env', 'POSTGRES_PASSWORD=' + self.secret + '\n')
        leaked = self.commit()
        self.git('checkout', '--quiet', original)
        result = self.checked()
        self.assertEqual(result['status'], 'findings')
        self.assertFalse(result['working_tree'])
        self.assertTrue(any(f['Commit'] == leaked and f['File'] == 'config/branch.env' for f in result['history']))

    def test_scanner_and_inventory_errors_fail_closed_without_raw_output(self):
        self.write('config/safe.properties', 'db.password=${DATABASE_PASSWORD}\n')
        self.commit()
        runner = ROOT / '.github/scripts/check-secrets.py'
        for error in ('invalid_config', 'invalid_inventory', 'unmatched_inventory', 'automatic_ignore'):
            with self.subTest(error=error):
                shutil.copyfile(ROOT / '.gitleaks.toml', self.repo / '.gitleaks.toml')
                policy = self.repo / SCANNER.EXCEPTIONS_FILE
                if policy.exists():
                    policy.unlink()  # Disposable test data only.
                ignore = self.repo / '.gitleaksignore'
                if ignore.exists():
                    ignore.unlink()
                if error == 'invalid_config':
                    self.write('.gitleaks.toml', 'invalid = [\n')
                elif error == 'invalid_inventory':
                    self.write(SCANNER.EXCEPTIONS_FILE, '{"version": 1, "fingerprints": ["*"]}')
                elif error == 'unmatched_inventory':
                    self.exceptions([{'Fingerprint': 'a' * 40 + ':README.md:test:1'}])
                else:
                    self.write('.gitleaksignore', 'README.md:test:1\n')
                result = subprocess.run([sys.executable, '-B', str(runner), '--repo', str(self.repo),
                                         '--gitleaks', EXECUTABLE], capture_output=True, timeout=30)
                self.assertEqual(result.returncode, 1)
                self.assertEqual(json.loads(result.stdout)['status'], 'error')
                self.assertNotIn(self.secret.encode(), result.stdout + result.stderr)

    def test_default_token_and_private_key_rules_remain_active(self):
        self.write('src/new.txt', 'gh' + 'p_' + secrets.token_hex(18) + '\n')
        self.write('keys/new.pem', '-----BEGIN ' + 'RSA PRIVATE KEY-----\n' + secrets.token_hex(40)
                   + '\n-----END ' + 'RSA PRIVATE KEY-----\n')
        self.commit()
        rules = {f['RuleID'] for f in self.detect()}
        self.assertTrue({'github-pat', 'private-key'} <= rules)

    def test_runner_blocks_history_and_versionable_working_tree_and_ignores_no_findings(self):
        self.write('config/first.env', 'POSTGRES_PASSWORD=' + self.secret + '\n')
        self.commit()
        baseline = self.detect()
        self.assertTrue(baseline, 'Initial unquoted credential escaped detection')
        self.write('.gitleaksignore', '\n'.join(f['Fingerprint'] for f in baseline) + '\n')
        self.write('config/first.env', '# removed\n')
        self.commit()
        untracked_secret = secrets.token_hex(16)
        self.write('config/untracked.env', 'POSTGRES_PASSWORD=' + untracked_secret + '\n')
        temporary = self.root / 'runner'
        temporary.mkdir()
        with self.assertRaises(RuntimeError):
            SCANNER.scan(self.repo.resolve(), EXECUTABLE, temporary)
        (self.repo / '.gitleaksignore').unlink()  # Only this disposable fixture.
        result = SCANNER.scan(self.repo.resolve(), EXECUTABLE, temporary)
        self.assertEqual(result['status'], 'findings')
        self.assertTrue(result['history'], 'A repository ignore file must not hide history')
        self.assertTrue(result['working_tree'], 'Versionable untracked credentials must be scanned')
        self.assertNotIn(self.secret, json.dumps(result))
        self.assertTrue(all(set(f) == set(SCANNER.FIELDS) for mode in ('history', 'working_tree') for f in result[mode]))

    def test_clean_runner_remains_successful(self):
        self.write('config/safe.properties', 'db.password=${DATABASE_PASSWORD}\n')
        self.commit()
        temporary = self.root / 'runner'
        temporary.mkdir()
        self.assertEqual(SCANNER.scan(self.repo.resolve(), EXECUTABLE, temporary)['status'], 'clean')

    def test_each_reviewed_fixture_is_limited_to_exact_value_rule_and_root_path(self):
        config = tomllib.loads((ROOT / '.gitleaks.toml').read_text())
        fixtures = {}
        for rule in config['rules']:
            for exception in rule.get('allowlists', []):
                if not exception['description'].startswith('Reviewed fixture:'):
                    continue
                self.assertEqual(exception['condition'], 'AND')
                self.assertEqual(len(exception['paths']), 1)
                path_pattern = exception['paths'][0]
                self.assertTrue(path_pattern.startswith('^') and path_pattern.endswith('$'))
                path = re.sub(r'\\(.)', r'\1', path_pattern[1:-1])
                pattern = exception['regexes'][0]
                self.assertTrue(pattern.startswith('^(?:') and pattern.endswith(')$'))
                values = [re.sub(r'\\(.)', r'\1', atom)
                          for atom in re.split(r'(?<!\\)\|', pattern[4:-2])]
                fixtures[path] = (rule['id'], values)
        self.assertEqual(len(fixtures), 10, 'Review the fixture inventory when changing exceptions')

        def assignment(rule, value):
            if rule == 'm2-unquoted-config-credential':
                return 'POSTGRES_PASSWORD: ' + value + '\n'
            return 'String password = "' + value + '";\n'

        for path, (rule, values) in fixtures.items():
            self.write(path, ''.join(assignment(rule, value) for value in values))
        self.commit()
        self.assertFalse(self.detect(), 'A reviewed fixture still blocks the history scan')
        temporary = self.root / 'runner'
        temporary.mkdir()
        self.assertEqual(SCANNER.scan(self.repo.resolve(), EXECUTABLE, temporary)['status'], 'clean')

        for path, (rule, values) in fixtures.items():
            self.write(path, assignment(rule, self.secret))
            # A matching suffix is insufficient: nested copies are new findings.
            self.write('nested/' + path, ''.join(assignment(rule, value) for value in values))
        token = 'gh' + 'p_' + secrets.token_hex(18)
        first = next(iter(fixtures))
        with (self.repo / first).open('a', encoding='utf-8') as stream:
            stream.write(token + '\n')
        self.commit()
        found = self.detect()
        paths = {f['File'] for f in found}
        self.assertTrue(set(fixtures) <= paths, 'A new value in a reviewed file escaped detection')
        self.assertTrue({'nested/' + path for path in fixtures} <= paths,
                        'A reviewed value escaped detection outside its exact root path')
        self.assertTrue(any(f['File'] == first and f['RuleID'] == 'github-pat' for f in found),
                        'A fixture exception must not suppress another secret rule')


if __name__ == '__main__':
    unittest.main()

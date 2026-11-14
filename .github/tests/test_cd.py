"""Run the actual CI policy/guard scripts with local fakes; no network or real Docker."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
WORKFLOW = (REPO / '.github/workflows/ci.yml').read_text(encoding='utf-8')
BASH = str(Path(r'C:\Program Files\Git\bin\bash.exe')) if os.name == 'nt' else shutil.which('bash')
DIGEST = 'ghcr.io/santimussi/front-desarrollo-apps-2@sha256:' + 'c' * 64
SHA = 'a' * 40
PRIVATE = 'PRIVATE_FIXTURE_MUST_NOT_APPEAR_IN_LOGS'

def shell_path(path):
    value = str(path).replace('\\', '/')
    return '/' + value[0].lower() + value[2:] if os.name == 'nt' else value

def workflow_step(name):
    lines = WORKFLOW.splitlines()
    start = lines.index('      - name: ' + name)
    selected = []
    collecting = False
    for line in lines[start + 1:]:
        if line.startswith('      - name:'):
            break
        if line == '        run: |':
            collecting = True
        elif collecting and (line.startswith('          ') or not line.strip()):
            selected.append(line[10:])
        elif collecting:
            break
    return '\n'.join(selected) + '\n'

class CDTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix='m2-cd-fixture-'))
        self.addCleanup(self.cleanup_root)
        self.bin = self.root / 'bin'; self.bin.mkdir()
        self.server = self.root / 'server'
        (self.server / 'compose').mkdir(parents=True)
        (self.server / 'scripts').mkdir()
        for name in ('.env', 'images.env', 'compose.deploy.yml'):
            (self.server / 'compose' / name).write_text('# local fixture only\n')
        self.called = self.root / 'deploy-called'
        self.config = self.root / 'config.json'
        self.environment = {
            'IDENTITY_PROVIDER': 'M1', 'APP_JWT_SECRET': PRIVATE,
            'CORE_OUTBOX_ENABLED': 'false', 'CORE_INBOX_ENABLED': 'false',
            'CORE_OUTBOX_AUTO_APPROVE_NEW_EVENTS': 'false', 'SIMULATOR_ENABLED': 'false',
            'CORE_OUTBOX_APPROVED_EVENT_IDS': '',
        }
        self.write_config()
        self.env = {**os.environ, 'PATH': str(self.bin) + os.pathsep + os.environ['PATH'],
                    'FAKE_CONFIG': str(self.config), 'FAKE_DEPLOY_CALL': str(self.called),
                    'FAKE_DOCKER_LOG': str(self.root / 'docker.log'),
                    'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': 'refs/heads/dev',
                    'GITHUB_SHA': SHA, 'GITHUB_REPOSITORY': 'owner/test',
                    'CD_IDENTITY_PROVIDER': 'M1', 'INPUT_IDENTITY_PROVIDER': '',
                    'GITHUB_OUTPUT': str(self.root / 'output')}
        # Git Bash on Windows needs a python3 shim; Linux runners already have python3.
        if os.name == 'nt':
            self.write(self.bin / 'python3', '#!/usr/bin/env bash\nexec "' + Path(sys.executable).as_posix() + '" "$@"\n')
        self.write(self.bin / 'docker', '''#!/usr/bin/env python3
import json, os, re, sys
from pathlib import Path
a=sys.argv[1:]
with Path(os.environ['FAKE_DOCKER_LOG']).open('a') as f: f.write(' '.join(a)+'\\n')
if a[0]=='compose':
 if a[-3:]==['config','--format','json']: print(Path(os.environ['FAKE_CONFIG']).read_text())
 elif a[-3:]==['ps','-q','backend']: print('a'*12)
 elif a[-3:]==['ps','-q','frontend']: print('b'*12)
 else: sys.exit(90)
elif a[0]=='inspect':
 template=a[2]
 if template=='{{.State.Running}}': print(os.getenv('FAKE_RUNNING','true'))
 elif template=='{{.Config.Image}}': print('ghcr.io/santimussi/front-desarrollo-apps-2@sha256:'+'d'*64)
 else:
  pair=re.search(r'if eq \\. "([^"]+)"',template).group(1)
  values={'IDENTITY_PROVIDER':os.getenv('FAKE_RUNNING_PROVIDER','M1'),
   'CORE_OUTBOX_ENABLED':'false','CORE_INBOX_ENABLED':'false',
   'CORE_OUTBOX_AUTO_APPROVE_NEW_EVENTS':'false','SIMULATOR_ENABLED':'false','CORE_OUTBOX_APPROVED_EVENT_IDS':''}
  key,value=pair.split('=',1)
  if values.get(key)==value and key!=os.getenv('FAKE_BAD_PAIR'): print('OK')
elif a[:2]==['image','inspect']: print(os.getenv('FAKE_FRONTEND_PROVIDER','M1'))
else: sys.exit(91)
''')
        self.write(self.bin / 'gh', '#!/usr/bin/env python3\nimport os\nprint(os.getenv("FAKE_HEAD", ""))\n')
        self.write(self.server / 'scripts/validate-integration-config.py',
                   'import json,os,sys\njson.load(sys.stdin)\nsys.exit(78 if os.getenv("FAKE_INVALID_CONFIG") else 0)\n')
        self.write(self.server / 'scripts/deploy-service.sh',
                   '#!/usr/bin/env bash\nprintf "%s\\n" "$@" > "$FAKE_DEPLOY_CALL"\nexit "${FAKE_DEPLOY_EXIT:-0}"\n')

    def cleanup_root(self):
        assert self.root.resolve().parent == Path(tempfile.gettempdir()).resolve()
        assert self.root.name.startswith('m2-cd-fixture-')
        shutil.rmtree(self.root)

    def write(self, path, content):
        path.write_text(content, encoding='utf-8', newline='\n')
        path.chmod(0o755)

    def write_config(self):
        self.config.write_text(json.dumps({'services': {'backend': {'environment': self.environment}}}))

    def execute(self, path, *args):
        result = subprocess.run([BASH, shell_path(path), *args], env=self.env,
                                capture_output=True, text=True, timeout=30)
        self.assertNotIn(PRIVATE, result.stdout + result.stderr)
        return result

    def resolve(self, event='push', ref='refs/heads/dev', cd='M1', manual=''):
        self.env.update(GITHUB_EVENT_NAME=event, GITHUB_REF=ref, CD_IDENTITY_PROVIDER=cd,
                        INPUT_IDENTITY_PROVIDER=manual)
        return self.execute(REPO / '.github/scripts/resolve-provider.sh')

    def deploy(self, service='frontend', selection=DIGEST, provider='M1'):
        return self.execute(REPO / '.github/scripts/deploy-cd.sh', shell_path(self.server), service, selection, provider)

    def blocked(self, result):
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.called.exists(), 'Guard delegated to deployment after rejecting configuration')

    def test_dev_requires_explicit_valid_provider_even_when_cd_paused(self):
        for value in ('', 'm1', 'M1;echo unsafe', 'unknown'):
            with self.subTest(value=value): self.assertNotEqual(self.resolve(cd=value).returncode, 0)

    def test_dev_selects_m1_and_explicit_mock(self):
        for value in ('M1', 'MOCK'):
            self.assertEqual(self.resolve(cd=value).returncode, 0)
            self.assertIn('identity_provider='+value, (self.root/'output').read_text())

    def test_pr_and_non_dev_push_do_not_require_cd_variables(self):
        for event,ref in (('pull_request','refs/pull/1/merge'),('push','refs/heads/main')):
            self.assertEqual(self.resolve(event,ref,cd='').returncode, 0)
            self.assertIn('identity_provider=MOCK', (self.root/'output').read_text())

    def test_manual_m1_overrides_cd_selection_and_preserves_main(self):
        self.assertEqual(self.resolve('workflow_dispatch','refs/heads/main',cd='MOCK',manual='M1').returncode, 0)
        self.assertIn('identity_provider=M1', (self.root/'output').read_text())
        self.assertNotEqual(self.resolve('workflow_dispatch','refs/heads/feature',manual='M1').returncode, 0)

    def test_manual_mock_remains_validation_mode(self):
        self.assertEqual(self.resolve('workflow_dispatch','refs/heads/dev',manual='MOCK').returncode, 0)
        self.assertIn('identity_provider=MOCK', (self.root/'output').read_text())

    def test_matching_m1_frontend_delegates_exact_digest(self):
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.called.read_text().splitlines(), ['frontend', DIGEST])
        self.assertEqual((self.server/'compose/.env').read_text(), '# local fixture only\n')

    def test_generic_backend_delegates_only_approved_sha(self):
        result = self.deploy('backend', SHA)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.called.read_text().splitlines(), ['backend', SHA])

    def test_explicit_mock_requires_matching_configuration_and_both_live_services(self):
        self.environment['IDENTITY_PROVIDER']='MOCK';self.write_config()
        self.env.update(FAKE_RUNNING_PROVIDER='MOCK',FAKE_FRONTEND_PROVIDER='MOCK')
        result=self.deploy(provider='MOCK')
        self.assertEqual(result.returncode,0,result.stderr)

    def test_digest_sha_and_provider_arguments_reject_mutable_or_injected_inputs(self):
        for service,selection,provider in (('frontend',SHA,'M1'),('frontend',DIGEST.replace('@sha256:',':latest#'),'M1'),
                ('frontend',DIGEST.replace('santimussi','other'),'M1'),('backend',DIGEST,'M1'),('frontend',DIGEST,'m1')):
            with self.subTest(service=service,selection=selection): self.blocked(self.deploy(service,selection,provider))

    def test_configured_provider_mismatch_is_blocked(self):
        self.environment['IDENTITY_PROVIDER']='MOCK';self.write_config()
        self.blocked(self.deploy())

    def test_each_configured_integration_flag_blocks_cd(self):
        for flag in ('CORE_OUTBOX_ENABLED','CORE_INBOX_ENABLED','CORE_OUTBOX_AUTO_APPROVE_NEW_EVENTS','SIMULATOR_ENABLED'):
            with self.subTest(flag=flag):
                self.environment[flag]='true';self.write_config();self.blocked(self.deploy());self.environment[flag]='false'

    def test_manual_event_list_blocks_without_printing_values(self):
        self.environment['CORE_OUTBOX_APPROVED_EVENT_IDS']=PRIVATE;self.write_config()
        self.blocked(self.deploy())

    def test_live_provider_stopped_backend_and_flags_are_checked(self):
        for key,value in (('FAKE_RUNNING_PROVIDER','MOCK'),('FAKE_RUNNING','false'),('FAKE_BAD_PAIR','CORE_INBOX_ENABLED')):
            with self.subTest(key=key):
                self.env[key]=value;self.blocked(self.deploy());self.env.pop(key)

    def test_running_frontend_label_mismatch_or_missing_is_blocked(self):
        for value in ('MOCK',''):
            self.env['FAKE_FRONTEND_PROVIDER']=value;self.blocked(self.deploy())

    def test_existing_validator_failure_is_not_ignored(self):
        self.env['FAKE_INVALID_CONFIG']='1';self.blocked(self.deploy())

    def test_deployer_rollback_exit_code_is_preserved(self):
        self.env['FAKE_DEPLOY_EXIT']='2'
        self.assertEqual(self.deploy().returncode, 2)

    def test_stale_dev_run_is_refused_by_actual_workflow_step(self):
        script=self.root/'stale.sh';self.write(script, workflow_step('Refuse stale dev runs'))
        self.env['FAKE_HEAD']='b'*40
        self.assertNotEqual(self.execute(script).returncode, 0)
        self.env['FAKE_HEAD']=SHA
        self.assertEqual(self.execute(script).returncode, 0)

    def test_provider_drift_and_invalid_digest_fail_before_ssh(self):
        name='Validate selected CD image and provider' if 'Frontend CI' in WORKFLOW else 'Validate selected CD provider'
        script=self.root/'selection.sh';self.write(script, workflow_step(name))
        self.env.update(SELECTED_PROVIDER='M1', CONFIGURED_CD_PROVIDER='MOCK', DEPLOY_IMAGE=DIGEST)
        self.assertNotEqual(self.execute(script).returncode, 0)
        self.env['CONFIGURED_CD_PROVIDER']='M1'
        self.assertEqual(self.execute(script).returncode, 0)
        if 'Frontend CI' in WORKFLOW:
            self.env['DEPLOY_IMAGE']='ghcr.io/santimussi/front-desarrollo-apps-2:dev'
            self.assertNotEqual(self.execute(script).returncode, 0)

    def test_workflow_preserves_manual_no_deploy_and_test_gates(self):
        deploy=WORKFLOW.split('\n  deploy:\n',1)[1]
        self.assertIn("github.event_name == 'push' && github.ref == 'refs/heads/dev' && vars.CD_ENABLED == 'true'", deploy)
        self.assertIn('StrictHostKeyChecking=yes', deploy)
        self.assertIn('if: always()', deploy)
        self.assertIn('< .github/scripts/deploy-cd.sh', deploy)
        if 'Frontend CI' in WORKFLOW:
            self.assertIn('needs: [configuration, validate, e2e]', WORKFLOW)
            self.assertIn('image: ${{ steps.record.outputs.image }}', WORKFLOW)
            self.assertIn('VITE_IDENTITY_PROVIDER: MOCK', WORKFLOW)
            self.assertIn('needs.publish.outputs.image', deploy)
        else:
            self.assertIn('./mvnw -B clean verify', WORKFLOW)
            self.assertIn('path: target/site/jacoco/', WORKFLOW)

    def test_frontend_m1_push_and_rerun_never_overwrite_mock_tags(self):
        if 'Frontend CI' not in WORKFLOW:self.skipTest('Frontend image tags only')
        script=self.root/'tags.sh';self.write(script,workflow_step('Select image tags'))
        self.env.update(BUILD_IDENTITY_PROVIDER='M1',IMAGE_REPOSITORY='ghcr.io/santimussi/front-desarrollo-apps-2',
                        GITHUB_RUN_ID='123',GITHUB_RUN_ATTEMPT='2')
        self.assertEqual(self.execute(script).returncode,0)
        output=(self.root/'output').read_text()
        self.assertIn('m1-sha-'+SHA+'-run-123-2',output)
        self.assertNotIn(':dev',output)
        self.assertNotIn(':sha-',output)

    def test_frontend_records_only_the_validated_digest_for_deployment(self):
        if 'Frontend CI' not in WORKFLOW:self.skipTest('Frontend digest output only')
        script=self.root/'record.sh';self.write(script,workflow_step('Record published image and digest'))
        self.env.update(BUILD_IDENTITY_PROVIDER='M1',IMAGE_REPOSITORY='ghcr.io/santimussi/front-desarrollo-apps-2',
                        IMAGE_REFERENCE='m1-locator',IMAGE_DIGEST='sha256:'+'c'*64,
                        GITHUB_STEP_SUMMARY=str(self.root/'summary'))
        self.assertEqual(self.execute(script).returncode,0)
        self.assertEqual((self.root/'output').read_text().strip(),'image='+DIGEST)
        (self.root/'output').unlink()
        self.env['IMAGE_DIGEST']=''
        self.assertNotEqual(self.execute(script).returncode,0)
        self.assertFalse((self.root/'output').exists())

if __name__ == '__main__':
    unittest.main()

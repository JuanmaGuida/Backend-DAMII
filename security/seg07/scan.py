"""SEG-07: bounded passive baseline / authenticated active scans in isolated Compose."""
import collections
import datetime
import hashlib
import html
import json
import os
import pathlib
import re
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = 'http://backend:8080'
API = 'http://127.0.0.1:8090'
OUT = pathlib.Path('/reports')
PUBLIC = ['/actuator/health', '/api/catalog/categories', '/api/catalog/neighborhoods',
          '/openapi.yaml', '/v3/api-docs', '/swagger-ui/index.html',
          '/swagger-ui/swagger-ui.css', '/swagger-ui/swagger-ui-bundle.js',
          '/swagger-ui/swagger-ui-standalone-preset.js', '/swagger-ui/swagger-initializer.js']
ROLES = {
    'CITIZEN': ('citizen@example.test', 'm1-dev-citizen', ['/api/me/tickets?page=0&size=5']),
    'AGENT': ('agent@example.test', 'm1-dev-agent', ['/api/tickets?page=0&size=5&search=seg07', '/api/staff/labels']),
    'AREA_RESPONSIBLE': ('area.responsible@example.test', 'm1-dev-area-responsible',
                         ['/api/staff/tickets/area?page=0&size=5&search=seg07']),
    'ADMIN': ('module.admin@example.test', 'm1-dev-module-admin', ['/api/admin/agents', '/api/staff/labels']),
}
DIRECT = urllib.request.build_opener(urllib.request.ProxyHandler({}))
PROXY = urllib.request.build_opener(urllib.request.ProxyHandler({'http': API}))
RESPONSE_HEADERS = {}
SECRETS = [v for k, v in os.environ.items() if k.startswith('SEG07_') and (k.endswith('PASSWORD') or k.endswith('KEY'))]
KEY = secrets.token_hex(32)
SECRETS.append(KEY)


def request(url, data=None, headers=None, proxy=False):
    req = urllib.request.Request(url, data=data, headers=headers or {})
    try:
        with (PROXY if proxy else DIRECT).open(req, timeout=40) as r:
            RESPONSE_HEADERS[url] = {h: r.headers.get(h) for h in ['Content-Type', 'Content-Security-Policy', 'X-Frame-Options', 'X-Content-Type-Options', 'Cache-Control', 'Strict-Transport-Security']}
            return r.status, r.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')


def api(component, kind, name, **params):
    params['apikey'] = KEY
    status, body = request(API + '/JSON/' + component + '/' + kind + '/' + name + '/?' + urllib.parse.urlencode(params))
    result = json.loads(body)
    if status != 200 or ('code' in result and 'message' in result):
        raise RuntimeError('ZAP API failed: ' + component + '/' + name + ' ' + str(clean(result)))
    return result


def clean(value):
    if isinstance(value, dict):
        return {k: clean(v) for k, v in value.items()}
    if isinstance(value, list):
        return [clean(v) for v in value]
    if not isinstance(value, str):
        return value
    for secret in SECRETS:
        value = value.replace(secret, '[REDACTED]')
    value = re.sub(r'(?i)Bearer\s+[^\s<>"\x27]+', 'Bearer [REDACTED]', value)
    value = re.sub(r'(?im)(set-cookie|cookie|authorization):[^\r\n]*', r'\1: [REDACTED]', value)
    value = re.sub(r'[\w.+-]+@[\w.-]+\.[a-zA-Z]{2,}', '[MOCK-EMAIL]', value)
    for name in ['Ciudadano de prueba', 'Agente de prueba', 'Responsable de área de prueba', 'Administrador de módulo de prueba']:
        value = value.replace(name, '[MOCK-IDENTITY]')
    return value


def save(name, value):
    (OUT / name).write_text(json.dumps(clean(value), ensure_ascii=False, indent=2))


def wait_passive():
    deadline = time.monotonic() + 180
    while int(api('pscan', 'view', 'recordsToScan')['recordsToScan']):
        if time.monotonic() > deadline:
            raise RuntimeError('Passive scanner timed out')
        time.sleep(2)


def identity(role, subject, checks, phase):
    status, body = request(BASE + '/api/auth/me', proxy=True)
    found = json.loads(body)
    assert status == 200 and found.get('subjectId') == subject and found.get('role') == role, 'Identity verification failed'
    if role == 'AREA_RESPONSIBLE':
        assert found.get('areaId') == 'M6', 'Wrong area'
    checks.append({'phase': phase, 'status': status, 'role': role, 'expected_subject': subject,
                   'subject_matches': True, 'area_matches': True,
                   'time_utc': datetime.datetime.now(datetime.timezone.utc).isoformat()})
    save('authentication-progress.json', {'checks': checks, 'complete': False})


def alert_key(alert):
    # Individual rule + endpoint + parameter; neither severity nor categories are suppressed.
    raw = '|'.join(str(alert.get(k, '')) for k in ['pluginId', 'alertRef', 'url', 'param', 'method', 'scan'])
    return hashlib.sha256(raw.encode()).hexdigest()[:20]


def verify_progress(progress):
    def strings(v):
        if isinstance(v, str):
            yield v
        elif isinstance(v, dict):
            for item in v.values():
                yield from strings(item)
        elif isinstance(v, list):
            for item in v:
                yield from strings(item)
    values = list(strings(progress))
    assert 'Complete' in values, 'No completed active rules'
    assert not any(v.startswith(('Skipped', 'Pending')) or re.fullmatch(r'\d+%', v) for v in values), 'Active rules incomplete or time-limited'


def collect(label):
    alerts = api('core', 'view', 'alerts', baseurl=BASE, start=0, count=100000)['alerts']
    assert len(alerts) < 100000, 'Alert export truncated'
    for alert in alerts:
        alert['scan'] = label
        alert['finding_key'] = alert_key(alert)
    return alerts


def evaluate(alerts, treatments):
    pending = []
    for a in alerts:
        if a['risk'] in ('High', 'Medium'):
            t = treatments.get(a['finding_key'], {})
            required = ['cause', 'classification', 'decision', 'justification', 'proposed_fix', 'evidence']
            valid = all(isinstance(t.get(k), str) and t[k].strip() for k in required)
            valid = valid and t.get('classification') in ('real', 'false_positive')
            valid = valid and t.get('decision') in ('accepted', 'false_positive', 'pending_fix')
            a['treatment'] = t if valid else {'decision': 'PENDING_ANALYSIS'}
            # A High always keeps this execution pending; correction requires a clean rerun.
            if a['risk'] == 'High' or not valid or t.get('decision') == 'pending_fix':
                pending.append(a['finding_key'])
    return pending


def export(alerts, coverage, auth, complete):
    counts = dict.fromkeys(['High', 'Medium', 'Low', 'Informational'], 0)
    for a in alerts:
        counts[a['risk']] += 1
    treatments = json.loads(pathlib.Path('/seg07/treatments.json').read_text())
    pending = evaluate(alerts, treatments)
    result = {'status': 'PASS' if complete and not pending else 'PENDING', 'complete': complete,
              'counts_alert_instances': counts, 'counts_unique_rules': {s: len({a['pluginId'] for a in alerts if a['risk'] == s}) for s in counts},
              'pending_findings': pending, 'coverage': coverage, 'authentication': auth,
              'zap_version': api('core', 'view', 'version')['version'],
              'image': 'ghcr.io/zaproxy/zaproxy:2.16.1@sha256:7840969c7c9fead565bf9734b12f49f6886db90b1d35b1f74d79710bbd081dab',
              'scope': 'Explicit GET allowlist only. No production, login attacks, uploads, writes, M1, Core or AI.',
              'alerts': alerts}
    save('report.json', result)
    safe = clean(result)
    # Human-readable counterpart of the sanitized ZAP API alerts, not a raw session export.
    content = '<!doctype html><html lang="es"><meta charset="utf-8"><title>SEG-07</title><h1>SEG-07</h1>'
    content += '<pre>' + html.escape(json.dumps({k:v for k,v in safe.items() if k != 'alerts'}, ensure_ascii=False, indent=2)) + '</pre>'
    for a in safe['alerts']:
        content += '<h2>' + html.escape(a['risk'] + ' — ' + a['alert']) + '</h2><pre>' + html.escape(json.dumps(a, ensure_ascii=False, indent=2)) + '</pre>'
    (OUT / 'report.html').write_text(content + '</html>')
    save('execution.json', {k: v for k, v in result.items() if k != 'alerts'})
    return result['status'] == 'PASS'


def main():
    mode = os.environ['SEG07_MODE']
    assert mode in ('baseline', 'active')
    OUT.mkdir(exist_ok=True)
    alerts, coverage, auth = [], [], []
    # Hard-coded target prevents accidental scanning of a caller-supplied shared URL.
    deadline = time.monotonic() + 240
    while True:
        try:
            if request(BASE + ('/actuator/health' if mode == 'baseline' else '/api/auth/me'))[0] == (200 if mode == 'baseline' else 401):
                break
        except OSError:
            pass
        if time.monotonic() > deadline:
            raise RuntimeError('Disposable backend not ready')
        time.sleep(3)
    with open('/tmp/seg07-zap.log', 'w') as log:
        process = subprocess.Popen(['/zap/zap.sh', '-daemon', '-host', '127.0.0.1', '-port', '8090',
                                    '-config', 'api.key=' + KEY, '-config', 'autoupdate.checkOnStart=false', '-config', 'connection.timeoutInSecs=15',
                                    '-config', 'scanner.threadPerHost=1', '-config', 'scanner.hostPerScan=1',
                                    '-config', 'scanner.delayInMs=150', '-config', 'scanner.maxRuleDurationInMins=3',
                                    '-config', 'scanner.maxScanDurationInMins=12'], stdout=log, stderr=log)
        try:
            deadline = time.monotonic() + 150
            while True:
                try:
                    api('core', 'view', 'version')
                    break
                except (OSError, ValueError):
                    if process.poll() is not None or time.monotonic() > deadline:
                        raise RuntimeError('ZAP daemon startup failed')
                    time.sleep(2)
            options = {}
            for name, expected in [('optionThreadPerHost', 1), ('optionHostPerScan', 1),
                                   ('optionDelayInMs', 150), ('optionMaxRuleDurationInMins', 3),
                                   ('optionMaxScanDurationInMins', 12)]:
                options[name] = int(next(iter(api('ascan', 'view', name).values())))
                assert options[name] == expected, 'ZAP concurrency/duration option mismatch: ' + name
            save('scanner-options.json', options)
            if mode == 'baseline':
                for path in PUBLIC:
                    status, _ = request(BASE + path, proxy=True)
                    assert status == 200, 'Public endpoint unavailable: ' + path
                    coverage.append({'method': 'GET', 'path': path, 'status': status, 'response_headers': RESPONSE_HEADERS.get(BASE + path, {})})
                wait_passive()
                alerts.extend(collect('baseline'))
            else:
                policy_before = api('ascan', 'view', 'scanners')['scanners']
                exclusions = {'40026': 'DOM/browser execution is outside this JSON REST scope; reflected and persistent XSS rules retained.', '40043': 'OAST Log4Shell probing excluded from network-isolated API policy; dependency inventory attached, no external callback service. Blind OAST behavior is not tested.', '50000': 'Custom-script dispatcher has no scripts supplied; built-in active rules retained.'}
                api('ascan', 'action', 'disableScanners', ids=','.join(exclusions))
                scoped_before = api('ascan', 'view', 'scanners')['scanners']
                original_flags = {p['id']: p['enabled'] for p in policy_before}
                assert all(p['enabled'] == original_flags[p['id']] for p in scoped_before if p['id'] not in exclusions), 'Unexpected rule exclusion'
                enabled = [p['id'] for p in scoped_before if p['enabled'] == 'true']
                assert enabled, 'No enabled active scan rules'
                for plugin_id in enabled:
                    api('ascan', 'action', 'setScannerAttackStrength', id=plugin_id, attackStrength='LOW')
                policy_after = api('ascan', 'view', 'scanners')['scanners']
                assert {p['id']: (p['enabled'], p['alertThreshold']) for p in scoped_before} == {p['id']: (p['enabled'], p['alertThreshold']) for p in policy_after}, 'Rule or threshold changed'
                assert all(p['attackStrength'] == 'LOW' for p in policy_after if p['enabled'] == 'true'), 'Attack strength mismatch'
                save('active-policy.json', {'before': policy_before, 'exclusions': exclusions, 'effective': policy_after, 'reason': 'Bounded API scan; Explicit JSON API policy: three individually justified optional capabilities excluded; all other enablement and thresholds preserved.'})
                for role, (username, subject, paths) in ROLES.items():
                    api('core', 'action', 'newSession', overwrite='true')
                    status, configured = request(BASE + '/__seg07_role', json.dumps({'role': role}).encode(), {'Content-Type': 'application/json', 'X-SEG07-Setup': os.environ['SEG07_SETUP_KEY']})
                    assert status == 200 and json.loads(configured)['role'] == role, 'Scope guard configuration failed'
                    # Controls go through ZAP and do not carry a cookie jar.
                    for rule in api('replacer', 'view', 'rules')['rules']:
                        api('replacer', 'action', 'removeRule', description=rule['description'])
                    for path in ['/api/auth/me'] + paths:
                        status, _ = request(BASE + path, proxy=True)
                        assert status == 401, 'Anonymous control unexpectedly authorized: ' + path
                    status, _ = request(BASE + '/api/auth/me', headers={'Authorization': 'Bearer seg07-invalid-control'}, proxy=True)
                    assert status == 401, 'Invalid token control unexpectedly authorized'
                    status, body = request(BASE + '/api/auth/login', json.dumps({'username': username,
                        'password': os.environ['SEG07_' + role + '_PASSWORD']}).encode(), {'Content-Type': 'application/json', 'X-SEG07-Setup': os.environ['SEG07_SETUP_KEY']})
                    login = json.loads(body)
                    assert status == 200 and login.get('tokenType') == 'Bearer' and login.get('token'), 'Mock login failed'
                    token = login['token']
                    SECRETS.append(token)
                    # Replacer applies to actual scanner traffic, including attack requests.
                    for rule in api('replacer', 'view', 'rules')['rules']:
                        api('replacer', 'action', 'removeRule', description=rule['description'])
                    api('replacer', 'action', 'addRule', description='seg07-bearer', enabled='true',
                        matchType='REQ_HEADER', matchRegex='false', matchString='Authorization',
                        replacement='Bearer ' + token, url=r'^http://backend:8080/.*')
                    checks = []
                    identity(role, subject, checks, 'before')
                    cid = api('context', 'action', 'newContext', contextName='seg07')['contextId']
                    allpaths = ['/api/auth/me'] + paths
                    regex = r'^http://backend:8080(?:' + '|'.join(re.escape(p.split('?')[0]) for p in allpaths) + r')(?:\?.*)?$'
                    api('context', 'action', 'includeInContext', contextName='seg07', regex=regex)
                    api('context', 'action', 'setContextInScope', contextName='seg07', booleanInScope='true')
                    for path in allpaths:
                        status, _ = request(BASE + path, proxy=True)
                        assert status == 200, 'Protected seed unavailable: ' + role + ' ' + path
                        coverage.append({'role': role, 'method': 'GET', 'path': path, 'status': status, 'response_headers': RESPONSE_HEADERS.get(BASE + path, {})})
                    scans = []
                    for path in paths:
                        sid = api('ascan', 'action', 'scan', url=BASE + path, recurse='false',
                                  inScopeOnly='true', contextId=cid, method='GET')['scan']
                        start = time.monotonic()
                        next_check = start
                        while int(api('ascan', 'view', 'status', scanId=sid)['status']) < 100:
                            if time.monotonic() - start > 810:
                                api('ascan', 'action', 'stop', scanId=sid)
                                raise RuntimeError('Active scan timed out')
                            if time.monotonic() >= next_check:
                                identity(role, subject, checks, 'during:' + path)
                                next_check = time.monotonic() + 15
                            time.sleep(2)
                        # ZAP's duration limit may stop rules: require all enabled rules finished.
                        progress = api('ascan', 'view', 'scanProgress', scanId=sid)
                        try:
                            verify_progress(progress)
                        except AssertionError:
                            save('incomplete-progress.json', {'role': role, 'path': path, 'progress': progress})
                            raise
                        scans.append({'path': path, 'scan_id': sid, 'progress': progress})
                    identity(role, subject, checks, 'after')
                    wait_passive()
                    scan_ids = {str(mid) for scan in scans for mid in api('ascan', 'view', 'messagesIds', scanId=scan['scan_id'])['messagesIds']}
                    authenticated = 0
                    blocked = 0
                    statuses = collections.Counter()
                    evaluated = collections.Counter()
                    for mid in scan_ids:
                        message = api('core', 'view', 'message', id=mid)['message']
                        header = message['requestHeader']
                        assert 'Authorization: Bearer ' + token in header, 'Scanner request without expected Bearer'
                        first = header.splitlines()[0].split()
                        target = urllib.parse.urlsplit(first[1])
                        assert (not target.netloc or target.netloc == 'backend:8080'), 'Out-of-scope host'
                        if target.path not in [p.split('?')[0] for p in allpaths] or first[0] != 'GET':
                            assert 'X-SEG07-Scope-Blocked: true' in message['responseHeader'], 'Out-of-scope request reached application'
                            blocked += 1
                            continue
                        authenticated += 1
                        statuses[message['responseHeader'].splitlines()[0].split()[1]] += 1
                        evaluated[first[0] + ' ' + target.path] += 1
                    assert authenticated > 0, 'No active scanner traffic'
                    # Retain counters, never raw messages, credentials, cookies or session files.
                    scope_status, scope_body = request(BASE + '/__seg07_scope', headers={'X-SEG07-Setup': os.environ['SEG07_SETUP_KEY']})
                    assert scope_status == 200, 'Scope guard evidence unavailable'
                    auth.append({'scope_guard': json.loads(scope_body), 'role': role, 'anonymous_control': 401, 'invalid_bearer_control': 401, 'checks': checks,
                                 'active_messages_with_expected_bearer': authenticated,
                                 'out_of_scope_messages_blocked_before_application': blocked,
                                 'active_response_statuses': dict(statuses), 'evaluated_requests': dict(evaluated),
                                 'scans': scans})
                    alerts.extend(collect('active:' + role))
                    export(alerts, coverage, auth, False)
            passed = export(alerts, coverage, auth, True)
            if mode == 'active':
                save('authentication-progress.json', {'complete': True, 'roles_verified': [a['role'] for a in auth]})
            print(json.dumps({'mode': mode, 'complete': True, 'acceptance': 'PASS' if passed else 'PENDING'}))
            return 0 if passed else 1
        except Exception:
            try:
                alerts.extend(collect('partial'))
                export(alerts, coverage, auth, False)
            except Exception:
                pass
            raise
        finally:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        # Avoid exception content: HTTP errors can contain credentials/attack payloads.
        save('execution.json', {'status': 'ERROR', 'complete': False, 'error_type': type(error).__name__, 'reason': str(error)})
        print('SEG-07 scanner failed (' + type(error).__name__ + '); acceptance pending.', file=sys.stderr)
        sys.exit(2)

import unittest
from scan import evaluate, clean, alert_key, SECRETS

class AcceptanceGateTest(unittest.TestCase):
    def setUp(self):
        self.alert = {'pluginId': '10038', 'url': 'http://backend:8080/swagger-ui/index.html',
                      'param': '', 'method': 'GET', 'risk': 'Medium', 'finding_key': 'individual'}
        self.treatment = {'cause': 'missing header', 'classification': 'real', 'decision': 'accepted',
                          'justification': 'documented isolated Swagger resource',
                          'proposed_fix': 'configure CSP after compatibility review', 'evidence': 'header absent'}

    def test_medium_without_analysis_blocks(self):
        self.assertEqual(evaluate([self.alert], {}), ['individual'])

    def test_incomplete_analysis_blocks(self):
        self.assertEqual(evaluate([self.alert], {'individual': {'decision': 'accepted'}}), ['individual'])

    def test_individual_medium_analysis_passes(self):
        self.assertEqual(evaluate([self.alert], {'individual': self.treatment}), [])

    def test_high_cannot_be_waived(self):
        a = dict(self.alert, risk='High')
        self.assertEqual(evaluate([a], {'individual': self.treatment}), ['individual'])

    def test_pending_fix_blocks(self):
        self.assertEqual(evaluate([self.alert], {'individual': dict(self.treatment, decision='pending_fix')}), ['individual'])

    def test_different_endpoint_requires_own_treatment(self):
        other = dict(self.alert, url='http://backend:8080/openapi.yaml', finding_key='other')
        self.assertNotEqual(alert_key(self.alert), alert_key(other))
        self.assertNotEqual(alert_key(dict(self.alert, alertRef='10055-5')), alert_key(dict(self.alert, alertRef='10055-6')))
        self.assertEqual(evaluate([self.alert, other], {'individual': self.treatment}), ['other'])

    def test_redaction(self):
        secret = 'synthetic-test-secret-123'
        SECRETS.append(secret)
        result = clean({'evidence': 'Authorization: Bearer ' + secret + '\nSet-Cookie: abc=secret\ncitizen@example.test'})
        self.assertNotIn(secret, str(result))
        self.assertNotIn('abc=secret', str(result))
        self.assertNotIn('citizen@example.test', str(result))


class ProgressTest(unittest.TestCase):
    def test_skipped_or_pending_rule_never_counts_as_complete(self):
        from scan import verify_progress
        for status in ['Skipped (max duration)', 'Pending', '98%']:
            with self.assertRaises(AssertionError):
                verify_progress({'scanProgress': [{'status': 'Complete'}, {'status': status}]})

    def test_empty_progress_is_not_success(self):
        from scan import verify_progress
        with self.assertRaises(AssertionError):
            verify_progress({'scanProgress': []})

class GuardTest(unittest.TestCase):
    def test_only_exact_get_routes_and_explicit_login_setup_pass(self):
        import os
        from unittest.mock import patch
        with patch.dict(os.environ, {'SEG07_MODE': 'active'}):
            import guard
            with patch.object(guard, 'ALLOWED', {'/api/me/tickets', '/api/auth/me'}):
                self.assertTrue(guard.allowed('GET', '/api/me/tickets'))
                for method, path in [('POST', '/api/me/tickets'), ('DELETE', '/api/me/tickets'),
                                     ('GET', '/.env'), ('GET', '/api/admin/agents'),
                                     ('GET', '/api/me/tickets/extra'), ('GET', '/api/me/%74ickets')]:
                    self.assertFalse(guard.allowed(method, path))
                self.assertFalse(guard.allowed('POST', '/api/auth/login'))
                self.assertTrue(guard.allowed('POST', '/api/auth/login', setup=True))

class ReviewTest(unittest.TestCase):
    def test_failed_scanner_cannot_be_accepted_by_offline_review(self):
        import json
        import pathlib
        import tempfile
        from review import review
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d)
            (p / 'report.json').write_text(json.dumps({'complete': True, 'alerts': []}))
            (p / 'execution.json').write_text(json.dumps({'status': 'ERROR', 'complete': False}))
            with self.assertRaises(ValueError):
                review(d)

class GuardHTTPTest(unittest.TestCase):
    def test_redirects_are_not_followed(self):
        import http.server, os, threading, urllib.error
        from unittest.mock import patch
        with patch.dict(os.environ, {'SEG07_MODE': 'active'}):
            import guard
        visited = []
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_GET(self):
                visited.append(self.path)
                self.send_response(302 if self.path == '/start' else 200)
                self.send_header('Location', '/forbidden')
                self.end_headers()
        server = http.server.HTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with self.assertRaises(urllib.error.HTTPError) as error:
                guard.CLIENT.open('http://127.0.0.1:' + str(server.server_port) + '/start')
            self.assertEqual(error.exception.code, 302)
            self.assertEqual(visited, ['/start'])
        finally:
            server.shutdown(); server.server_close(); thread.join()

    def test_unknown_and_write_methods_never_reach_application(self):
        import http.server, os, threading, urllib.error, urllib.request
        from unittest.mock import patch
        with patch.dict(os.environ, {'SEG07_MODE': 'active', 'SEG07_SETUP_KEY': 'unit-test-only'}):
            import guard
            server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), guard.Guard)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                with patch.object(guard.CLIENT, 'open') as upstream:
                    for method in ['PROPFIND', 'DELETE', 'POST']:
                        req = urllib.request.Request('http://127.0.0.1:' + str(server.server_port) + '/api/me/tickets', method=method)
                        with self.assertRaises(urllib.error.HTTPError) as error:
                            urllib.request.urlopen(req)
                        self.assertEqual(error.exception.code, 403)
                        self.assertEqual(error.exception.headers['X-SEG07-Scope-Blocked'], 'true')
                    upstream.assert_not_called()
            finally:
                server.shutdown(); server.server_close(); thread.join()

if __name__ == '__main__':
    unittest.main()

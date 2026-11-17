"""Network boundary: only the authorized GET routes reach the disposable application."""
import collections
import http.server
import json
import os
import threading
import urllib.error
import urllib.request
from scan import PUBLIC, ROLES

ALLOWED = set(PUBLIC if os.environ['SEG07_MODE'] == 'baseline' else ['/api/auth/me'] +
              [p.split('?')[0] for _, _, paths in ROLES.values() for p in paths])
COUNTS = collections.Counter()
LOCK = threading.Lock()
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


CLIENT = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
HOP = {'connection', 'transfer-encoding', 'keep-alive', 'proxy-authenticate',
       'proxy-authorization', 'te', 'trailers', 'upgrade', 'content-length'}


def allowed(method, path, setup=False):
    return (method == 'GET' and path in ALLOWED) or (os.environ['SEG07_MODE'] == 'active' and
            method == 'POST' and path == '/api/auth/login' and setup)


class Guard(http.server.BaseHTTPRequestHandler):
    def __getattr__(self, name):
        if name.startswith('do_'):
            return self.handle_request
        raise AttributeError(name)

    def log_message(self, *_):
        pass  # Never log request bodies, headers, credentials or attack URLs.

    def small(self, code, body, blocked=False):
        body = json.dumps(body).encode()
        self.send_response_only(code)
        for key, value in {'Content-Type':'application/json', 'Content-Length':str(len(body)),
                           'Cache-Control':'no-store', 'X-Content-Type-Options':'nosniff',
                           'X-Frame-Options':'DENY', 'Content-Security-Policy':"default-src 'none'"}.items():
            self.send_header(key, value)
        if blocked:
            self.send_header('X-SEG07-Scope-Blocked', 'true')
        self.end_headers()
        self.wfile.write(body)

    def handle_request(self):
        global ALLOWED
        path = self.path.split('?', 1)[0]
        setup = self.headers.get('X-SEG07-Setup') == os.environ['SEG07_SETUP_KEY']
        if self.command == 'POST' and path == '/__seg07_role' and setup and os.environ['SEG07_MODE'] == 'active':
            role = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))))['role']
            if role not in ROLES:
                return self.small(400, {'code': 'INVALID_ROLE'})
            ALLOWED = {'/api/auth/me'} | {p.split('?')[0] for p in ROLES[role][2]}
            return self.small(200, {'role': role, 'allowed_get': sorted(ALLOWED)})
        if self.command == 'GET' and path == '/__seg07_scope' and setup:
            with LOCK:
                counts = {'counts': dict(COUNTS), 'allowed_get': sorted(ALLOWED)}
            return self.small(200, counts)
        if not allowed(self.command, path, setup):
            with LOCK:
                COUNTS['blocked_before_application'] += 1
            return self.small(403, {'code':'SEG07_SCOPE_BLOCKED'}, True)
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if length > 16384 or length < 0:
                return self.small(413, {'code':'SEG07_SCOPE_BLOCKED'}, True)
            data = self.rfile.read(length) if length else None
            headers = {k:v for k,v in self.headers.items() if k.lower() not in HOP | {'x-seg07-setup'}}
            headers.setdefault('Host', 'backend:8080')
            req = urllib.request.Request('http://application:8080' + self.path, data=data, headers=headers, method=self.command)
            try:
                response = CLIENT.open(req, timeout=35)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                body = response.read()
                self.send_response_only(response.code)
                for k,v in response.headers.items():
                    if k.lower() not in HOP:
                        self.send_header(k,v)
                self.send_header('Content-Length',str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            with LOCK:
                COUNTS['forwarded:' + self.command + ':' + path] += 1
        except (OSError, ValueError):
            self.small(502, {'code':'SEG07_GUARD_UPSTREAM_FAILED'})

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = do_HEAD = do_OPTIONS = do_TRACE = do_CONNECT = handle_request


if __name__ == '__main__':
    http.server.ThreadingHTTPServer(('0.0.0.0', 8080), Guard).serve_forever()

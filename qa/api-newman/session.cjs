module.exports = ({ scenario }) => {
  const login = '/api/auth/login';
  const refresh = '/api/auth/refresh';
  const logout = '/api/auth/logout';
  const origin = { key: 'Origin', value: 'https://m2.example.test' };
  const csrf = { key: 'X-M2-CSRF', value: '1' };
  const cookie = { key: 'Cookie', value: '{{m1SessionCookie}}' };
  const session = (id, route, status, options) => scenario(id, 'POST', route, status, 'Sesión M1 simulada en loopback', { profile: 'm1', ...options });
  const cleared = 'pm.test("session cookie was cleared", () => pm.expect(pm.response.headers.get("Set-Cookie")).to.include("Max-Age=0"));';
  return [
    session('m1-refresh-without-cookie', refresh, 401, { headers: [origin, csrf], checks: [cleared] }),
    session('m1-login', login, 200, {
      headers: [origin], body: { username: 'session-qa@example.test', password: '{{m1FixtureCredential}}' },
      checks: [
        'pm.test("M1 cookie is secured", () => { const value = pm.response.headers.get("Set-Cookie"); pm.expect(value).to.include("HttpOnly"); pm.expect(value).to.include("Secure"); });',
        'if (pm.response.code === 200) pm.environment.set("m1SessionCookie", pm.response.headers.get("Set-Cookie").split(";")[0]);'
      ]
    }),
    session('m1-refresh', refresh, 200, {
      headers: [origin, csrf, cookie], checks: [
        'pm.test("M1 cookie rotated", () => { const value = pm.response.headers.get("Set-Cookie").split(";")[0]; pm.expect(value).to.not.eql(pm.environment.get("m1SessionCookie")); pm.environment.set("m1SessionCookie", value); });'
      ]
    }),
    session('m1-logout-without-csrf', logout, 403, {
      headers: [origin, cookie], checks: [
        'pm.test("invalid logout does not clear cookie", () => pm.expect(pm.response.headers.has("Set-Cookie")).to.eql(false));'
      ]
    }),
    session('m1-logout', logout, 204, { headers: [origin, csrf, cookie], checks: [cleared] })
  ];
};

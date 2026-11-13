// Real HTTPS protocol checks using the actual React session/client modules, without a browser UI.
// Run only through the private launcher. Credentials, cookies and JWTs remain in memory.
import { readFile, writeFile } from 'node:fs/promises';
import { request } from 'node:https';
import { resolve } from 'node:path';
import process from 'node:process';

const origin = process.env.M2_SESSION_CHECK_ORIGIN;
const reportFile = process.env.M2_SESSION_CHECK_REPORT;
const frontend = process.env.M2_SESSION_CHECK_FRONTEND;
const expectedRunId = process.env.M2_SESSION_CHECK_RUN_ID;
const result = { outcome: 'FAIL', cycles: [], checks: [] };
let cookie = '';
const check = (condition, label) => { if (!condition) throw new Error(label); result.checks.push(label); };
try {
  check(/^https:\/\/localhost:[0-9]{4,5}$/.test(origin || ''), 'local_https_origin');
  check(Boolean(frontend && reportFile), 'private_launcher_configuration');
  check(Boolean(expectedRunId), 'current_lab_run_required');
  const ca = await readFile(resolve(process.env.LOCALAPPDATA, 'M2Lab/ca/rootCA.pem'));
  const fetchLocal = (url, options = {}) => new Promise((yes, no) => {
    const target = new URL(url, origin);
    if (target.origin !== origin) { no(new Error('external_request_rejected')); return; }
    const headers = { ...options.headers, Origin: origin };
    if (cookie) headers.Cookie = cookie;
    const req = request(target, { ca, family: 4, method: options.method || 'GET', headers, signal: options.signal }, response => {
      try {
        if (response.headers['x-m2-lab-run'] !== expectedRunId) {
          throw Object.assign(new Error('Unexpected lab instance'), { code: 'LAB_RUN_MISMATCH' });
        }
        for (const value of response.headers['set-cookie'] || []) {
          check(value.startsWith('__Host-M2-Refresh=') && value.includes('Secure') && value.includes('HttpOnly')
            && value.includes('SameSite=Strict') && value.includes('Path=/') && !value.includes('Domain='), 'secure_cookie_attributes');
          cookie = /Max-Age=0(?:;|$)/.test(value) ? '' : value.split(';', 1)[0];
        }
      } catch (error) { response.resume(); no(error); return; }
      const parts = [];
      response.on('data', part => parts.push(part)); response.on('error', no);
      response.on('end', () => yes(new Response(response.statusCode === 204 ? null : Buffer.concat(parts), { status: response.statusCode })));
    });
    req.on('error', no); req.setTimeout(45000, () => req.destroy(new Error('local_request_timeout')));
    req.end(options.body);
  });
  let ready = false;
  for (let attempt = 0; attempt < 120; attempt++) {
    try { if ((await fetchLocal('/api/catalog/categories')).status === 200) { ready = true; break; } }
    catch (error) { if (error.code === 'LAB_RUN_MISMATCH') throw error; }
    await new Promise(done => setTimeout(done, 2000));
  }
  check(ready, 'proxy_and_backend_ready');
  globalThis.fetch = fetchLocal;
  globalThis.window = new EventTarget();
  const storage = new Map();
  globalThis.localStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) };
  const sessionSource = (await readFile(resolve(frontend, 'src/services/authSession.js'), 'utf8'))
    .replaceAll('import.meta.env.VITE_IDENTITY_PROVIDER', '"M1"').replaceAll('import.meta.env.VITE_API_BASE_URL', '"/api"');
  const sessionUrl = `data:text/javascript;base64,${Buffer.from(sessionSource).toString('base64')}`;
  const clientSource = (await readFile(resolve(frontend, 'src/services/apiClient.js'), 'utf8')).replaceAll('"./authSession"', JSON.stringify(sessionUrl));
  const auth = await import(sessionUrl);
  const api = await import(`data:text/javascript;base64,${Buffer.from(clientSource).toString('base64')}`);
  const identities = [];
  for (const [account, variable] of [['ciudadano1.m2@example.com', 'M1_TEST_CITIZEN1_PASSWORD'], ['ciudadano2.m2@example.com', 'M1_TEST_CITIZEN2_PASSWORD']]) {
    check(Boolean(process.env[variable]), 'authorized_private_credential_present');
    const login = await api.login({ username: account, password: process.env[variable] });
    check(login.identity.actorType === 'CIUDADANO' && Boolean(login.identity.citizenId), 'real_citizen_login');
    identities.push(login.identity.actorId);
    check((await api.fetchCurrentUser()).actorId === login.identity.actorId, 'authenticated_identity');
    const refreshed = await auth.refreshSession({ force: true });
    check(refreshed.identity.actorId === login.identity.actorId, 'real_refresh_same_identity');
    const catalogDuringLogout = api.fetchCategories();
    await auth.logoutSession();
    check(Array.isArray(await catalogDuringLogout), 'public_catalog_during_logout');
    check(!cookie && auth.getStoredToken() === null && storage.size === 0, 'logout_clears_cookie_and_memory');
    check((await fetchLocal('/api/auth/refresh', { method: 'POST', headers: { 'X-M2-CSRF': '1' } })).status === 401, 'no_session_restoration_after_logout');
    check(Array.isArray(await api.fetchCategories()), 'public_catalog_after_logout');
    check((await fetchLocal('/api/catalog/categories')).status === 200, 'backend_and_proxy_alive_after_logout');
    result.cycles.push({ account, outcome: 'PASS' });
  }
  check(identities[0] !== identities[1], 'two_distinct_citizens');
  result.outcome = 'PASS';
} catch (error) {
  // No stack/message from libraries or data URLs: they can embed modules or private values.
  result.failure = result.checks.length;
  result.code = /^[A-Z0-9_]+$/.test(error?.code || '') ? error.code : 'CHECK_FAILED';
  process.exitCode = 1;
} finally {
  if (reportFile) await writeFile(reportFile, JSON.stringify(result, null, 2));
  console.log(JSON.stringify({ outcome: result.outcome, completedCycles: result.cycles.length, code: result.code }));
}

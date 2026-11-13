import assert from 'node:assert/strict';
import { test, before, after } from 'node:test';
import { createServer } from 'node:http';
import { request } from 'node:https';
import { spawn, spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { mkdtemp, writeFile, readFile, rm, mkdir, access } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, dirname, basename, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { once } from 'node:events';
import process from 'node:process';

let fixture, cert, backend;
const children = new Set();
const origins = new Set(['https://localhost:18443']);
let authenticationRequests = 0;
const frontendServices = fileURLToPath(new URL('../../../Frontend/front-desarrollo-apps-2/Front/frontend-atencion-ciudadana/src/services/', import.meta.url));
before(async () => {
  fixture = await mkdtemp(join(tmpdir(), 'm2-proxy-test-'));
  await writeFile(join(fixture, 'index.html'), '<html><script src="/assets/test.js"></script></html>');
  const openssl = process.env.OPENSSL_BIN || (process.platform === 'win32' ? 'C:/Program Files/Git/usr/bin/openssl.exe' : 'openssl');
  const result = spawnSync(openssl, ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
    '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost',
    '-keyout', join(fixture, 'key.pem'), '-out', join(fixture, 'cert.pem')], { windowsHide: true, stdio: 'ignore' });
  assert.equal(result.status, 0, 'Synthetic TLS fixture generation requires OpenSSL');
  cert = await readFile(join(fixture, 'cert.pem'));
  await mkdir(join(fixture, 'M2Lab/ca'), { recursive: true });
  await writeFile(join(fixture, 'M2Lab/ca/rootCA.pem'), cert);
  backend = createServer((req, res) => {
    if (req.url.startsWith('/api/auth/')) authenticationRequests++;
    if (req.url === '/api/truncated') {
      res.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': '1000' });
      res.write('{"incomplete":');
      setTimeout(() => res.destroy(), 20);
    } else if (req.url === '/api/unavailable') {
      res.destroy();
    } else if (req.url === '/api/slow') {
      res.writeHead(200, { 'Content-Type': 'text/plain' }); res.write('started');
      res.on('close', () => backend.emit('client-cancelled'));
    } else {
      assert.ok(origins.has(req.headers.origin));
      if (req.method === 'POST') assert.equal(req.headers['x-m2-csrf'], '1');
      if (req.url.startsWith('/api/auth/')) {
        let body = '';
        req.on('data', part => { body += part; });
        req.on('end', () => {
          const number = req.url.endsWith('/login') ? (JSON.parse(body).username.includes('ciudadano1') ? 1 : 2)
            : (req.headers.cookie?.includes('citizen1') || req.headers.authorization?.endsWith('citizen1') ? 1 : 2);
          const id = `synthetic-citizen${number}`;
          const identity = { actorId: id, actorType: 'CIUDADANO', citizenId: id, role: 'CITIZEN' };
          if (req.url.endsWith('/logout')) {
            res.writeHead(204, { 'Set-Cookie': '__Host-M2-Refresh=; Max-Age=0; Secure; HttpOnly; SameSite=Strict; Path=/' }); res.end();
          } else if (req.url.endsWith('/refresh') && !req.headers.cookie) {
            res.writeHead(401); res.end('{"code":"INVALID_TOKEN"}');
          } else {
            res.writeHead(200, { 'Content-Type': 'application/json', 'Set-Cookie': `__Host-M2-Refresh=${id}; Secure; HttpOnly; SameSite=Strict; Path=/` });
            res.end(JSON.stringify(req.url.endsWith('/me') ? identity : { identity, token: id, expiresAt: new Date(Date.now() + 900000).toISOString() }));
          }
        });
        return;
      }
      res.writeHead(200, { 'Content-Type': 'application/json', 'X-M2-Lab-Run': 'upstream-cannot-override-proxy-identity',
        'Set-Cookie': '__Host-M2-Refresh=synthetic; Secure; HttpOnly; SameSite=Strict; Path=/' });
      res.end('[]');
    }
  });
  backend.listen(0, '127.0.0.1'); await once(backend, 'listening');
});
after(async () => {
  for (const child of children) child.kill();
  backend?.closeAllConnections(); if (backend?.listening) await new Promise(resolve => backend.close(resolve));
  if (fixture) {
    assert.equal(dirname(fixture), resolve(tmpdir()));
    assert.ok(basename(fixture).startsWith('m2-proxy-test-'));
    await rm(fixture, { recursive: true, force: true });
  }
});
async function unusedPort() {
  const portFinder = createServer(); portFinder.listen(0, '127.0.0.1'); await once(portFinder, 'listening');
  const port = portFinder.address().port; await new Promise(resolve => portFinder.close(resolve));
  return port;
}
function startProxy(port, extraEnv = {}) {
  origins.add(`https://localhost:${port}`);
  const child = spawn(process.execPath, [fileURLToPath(new URL('./m1-lab-https.mjs', import.meta.url))], {
    env: { ...process.env, M2_LAB_CERT_FILE: join(fixture, 'cert.pem'), M2_LAB_KEY_FILE: join(fixture, 'key.pem'),
      M2_LAB_FRONTEND_DIST: fixture, M2_LAB_HTTPS_PORT: String(port), M2_LAB_BACKEND_PORT: String(backend.address().port),
      M2_LAB_BACKEND_PID: String(process.pid), M2_LAB_RUN_ID: `test-run-${port}`, M2_LAB_VERIFY_SESSIONS: '', ...extraEnv },
    windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']
  });
  children.add(child); let stderr = '';
  child.stderr.on('data', data => { stderr += data; });
  return { port, child, stderr: () => stderr };
}
async function launch(extraEnv = {}) {
  const proxy = startProxy(await unusedPort(), extraEnv);
  const { child } = proxy;
  await Promise.race([once(child.stdout, 'data'), once(child, 'exit').then(() => { throw new Error('Proxy startup failed'); }),
    new Promise((_, reject) => setTimeout(() => reject(new Error('Proxy startup timed out')), 10000).unref())]);
  return proxy;
}
async function exited(child) {
  if (child.exitCode !== null) return child.exitCode;
  return (await Promise.race([once(child, 'exit'), new Promise((_, reject) => setTimeout(() => reject(new Error('Child did not exit')), 10000).unref())]))[0];
}
function get(port, path, { cancel = false } = {}) {
  return new Promise((resolve, reject) => {
    const req = request({ hostname: 'localhost', family: 4, port, path, ca: cert,
      headers: { Origin: 'https://localhost:18443', 'X-M2-CSRF': '1' } }, res => {
      res.on('error', () => resolve({ interrupted: true }));
      res.on('data', () => { if (cancel) { req.destroy(); resolve({ cancelled: true }); } });
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers }));
    });
    req.setTimeout(3000, () => req.destroy(new Error('Synthetic request timed out')));
    req.on('error', reject); req.end();
  });
}
test('a truncated upstream response does not kill the HTTPS proxy or masquerade as a complete response', async () => {
  const proxy = await launch();
  const response = await get(proxy.port, '/api/truncated');
  assert.equal(response.interrupted, true);
  await new Promise(resolve => setTimeout(resolve, 100));
  assert.equal(proxy.child.exitCode, null, proxy.stderr());
  const next = await get(proxy.port, '/api/catalog/categories');
  assert.equal(next.status, 200);
  assert.match(next.headers['set-cookie'][0], /Secure; HttpOnly; SameSite=Strict/);
  assert.equal(next.headers['x-m2-lab-run'], `test-run-${proxy.port}`);
});
test('navigation cancellation closes the upstream and the next anonymous page remains available', async () => {
  const proxy = await launch();
  const cancelled = Promise.race([once(backend, 'client-cancelled').then(() => true),
    new Promise(resolve => setTimeout(() => resolve(false), 1000))]);
  await get(proxy.port, '/api/slow', { cancel: true });
  assert.equal(await cancelled, true, 'Client cancellation must release the upstream connection');
  assert.equal((await get(proxy.port, '/')).status, 200);
  assert.equal(proxy.child.exitCode, null, proxy.stderr());
});
test('an upstream disconnect before headers returns 503 and the proxy continues serving', async () => {
  const proxy = await launch();
  assert.equal((await get(proxy.port, '/api/unavailable')).status, 503);
  assert.equal((await get(proxy.port, '/api/catalog/categories')).status, 200);
  assert.equal(proxy.child.exitCode, null);
  assert.match(proxy.stderr(), /upstream_failure/);
  assert.doesNotMatch(proxy.stderr(), /__Host-M2-Refresh|Cookie|Bearer/);
});
test('repeated cancellations and catalog reloads keep the proxy alive', async () => {
  const proxy = await launch();
  for (let cycle = 0; cycle < 5; cycle++) {
    await Promise.all(Array.from({ length: 20 }, () => get(proxy.port, '/api/slow', { cancel: true })));
    const responses = await Promise.all(Array.from({ length: 20 }, () => get(proxy.port, '/api/catalog/categories')));
    assert.ok(responses.every(response => response.status === 200));
  }
  assert.equal(proxy.child.exitCode, null, proxy.stderr());
});
test('a port lost after preflight never launches verification against the existing proxy', async () => {
  const existing = await launch();
  const report = join(fixture, 'port-collision.json');
  const before = authenticationRequests;
  const failed = startProxy(existing.port, { M2_LAB_VERIFY_SESSIONS: 'YES', M2_LAB_RUN_ID: 'new-run',
    LOCALAPPDATA: fixture, M2_SESSION_CHECK_ORIGIN: `https://localhost:${existing.port}`,
    M2_SESSION_CHECK_FRONTEND: fixture, M2_SESSION_CHECK_REPORT: report });
  assert.notEqual(await exited(failed.child), 0);
  assert.match(failed.stderr(), /EADDRINUSE/);
  await assert.rejects(access(report));
  assert.equal(authenticationRequests, before);
  assert.equal((await get(existing.port, '/api/catalog/categories')).headers['x-m2-lab-run'], `test-run-${existing.port}`);
  assert.equal(existing.child.exitCode, null);
});
test('catalog 200 from another run is rejected before any authentication request', async () => {
  const existing = await launch();
  const report = join(fixture, 'wrong-run.json');
  const before = authenticationRequests;
  const child = spawn(process.execPath, [fileURLToPath(new URL('./verify-m1-session-cycles.mjs', import.meta.url))], {
    env: { ...process.env, LOCALAPPDATA: fixture, M2_SESSION_CHECK_ORIGIN: `https://localhost:${existing.port}`,
      M2_SESSION_CHECK_RUN_ID: 'different-run', M2_SESSION_CHECK_FRONTEND: fixture, M2_SESSION_CHECK_REPORT: report },
    windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']
  });
  children.add(child); child.stdout.resume(); child.stderr.resume();
  assert.equal(await exited(child), 1);
  const result = JSON.parse(await readFile(report, 'utf8'));
  assert.equal(result.code, 'LAB_RUN_MISMATCH');
  assert.equal(result.cycles.length, 0);
  assert.equal(authenticationRequests, before);
  assert.equal(existing.child.exitCode, null);
});
test('missing owned backend prevents the proxy and verifier from starting', async () => {
  const report = join(fixture, 'missing-backend.json');
  const before = authenticationRequests;
  const proxy = startProxy(await unusedPort(), { M2_LAB_VERIFY_SESSIONS: 'YES', M2_LAB_BACKEND_PID: '0',
    LOCALAPPDATA: fixture, M2_SESSION_CHECK_FRONTEND: fixture, M2_SESSION_CHECK_REPORT: report });
  proxy.child.stdout.resume();
  assert.notEqual(await exited(proxy.child), 0);
  await assert.rejects(access(report));
  assert.equal(authenticationRequests, before);
});
test('verification starts after binding, accepts only its run, and propagates verification failure', async () => {
  const port = await unusedPort();
  const report = join(fixture, 'bound-run.json');
  const proxy = startProxy(port, { M2_LAB_VERIFY_SESSIONS: 'YES', LOCALAPPDATA: fixture,
    M2_SESSION_CHECK_ORIGIN: `https://localhost:${port}`, M2_SESSION_CHECK_FRONTEND: fixture, M2_SESSION_CHECK_REPORT: report });
  proxy.child.stdout.resume();
  // The matching proxy is reached. This fixture deliberately has no React modules, so verification must fail visibly.
  assert.equal(await exited(proxy.child), 1);
  const result = JSON.parse(await readFile(report, 'utf8'));
  assert.ok(result.checks.includes('proxy_and_backend_ready'));
  assert.equal(result.code, 'ENOENT');
  assert.match(proxy.stderr(), /verifier_failed/);
});
test('a bound run verifies two synthetic sessions and keeps the same proxy alive', { skip: !existsSync(join(frontendServices, 'authSession.js')) }, async () => {
  const frontend = join(fixture, 'real-client');
  await mkdir(join(frontend, 'src/services'), { recursive: true });
  for (const name of ['authSession.js', 'apiClient.js']) {
    await writeFile(join(frontend, 'src/services', name), await readFile(join(frontendServices, name)));
  }
  const port = await unusedPort();
  const report = join(fixture, 'successful-run.json');
  const proxy = startProxy(port, { M2_LAB_VERIFY_SESSIONS: 'YES', LOCALAPPDATA: fixture,
    M2_SESSION_CHECK_ORIGIN: `https://localhost:${port}`, M2_SESSION_CHECK_FRONTEND: frontend, M2_SESSION_CHECK_REPORT: report,
    M1_TEST_CITIZEN1_PASSWORD: 'synthetic', M1_TEST_CITIZEN2_PASSWORD: 'synthetic' });
  proxy.child.stdout.resume();
  let result;
  for (let attempt = 0; attempt < 100; attempt++) {
    result = await readFile(report, 'utf8').then(JSON.parse).catch(() => null);
    if (result) break;
    await new Promise(done => setTimeout(done, 50));
  }
  assert.equal(result?.outcome, 'PASS', proxy.stderr());
  assert.equal(result.cycles.length, 2);
  assert.equal(proxy.child.exitCode, null, proxy.stderr());
  assert.equal((await get(port, '/api/catalog/categories')).status, 200);
});
test('PowerShell rejects occupied ports before creating a lab or starting verification', async () => {
  const fakeTools = join(fixture, 'tools');
  await mkdir(join(fakeTools, 'bin'), { recursive: true });
  await mkdir(join(fixture, 'node_modules/vite/bin'), { recursive: true });
  for (const file of ['initdb.exe', 'pg_ctl.exe', 'createdb.exe']) await writeFile(join(fakeTools, file), 'not executable');
  await writeFile(join(fakeTools, 'bin/java.exe'), 'not executable');
  await writeFile(join(fixture, 'node_modules/vite/bin/vite.js'), '');
  const runner = fileURLToPath(new URL('./test-m1-isolated.ps1', import.meta.url));
  const backendPort = await unusedPort();
  let httpsPort;
  do { httpsPort = await unusedPort(); } while (httpsPort === backendPort);
  const script = `try { & $env:TEST_LAB_RUNNER -RealM1 -ProvisionEmployee -Serve -VerifySessions -DatabasePort ${backend.address().port} -BackendPort ${backendPort} -HttpsPort ${httpsPort} -PostgresBin $env:TEST_FAKE_TOOLS -JavaHome $env:TEST_FAKE_TOOLS -FrontendPath $env:TEST_FIXTURE; exit 9 } catch { $cause=$_.Exception; while($cause.InnerException){$cause=$cause.InnerException}; if ($cause -is [Net.Sockets.SocketException]) { 'OCCUPIED_PORT_REJECTED'; exit 0 }; $cause.GetType().FullName; exit 8 }`;
  const child = spawn(process.env.PWSH_BIN || 'pwsh', ['-NoLogo', '-NoProfile', '-Command', script], {
    env: { ...process.env, TEST_LAB_RUNNER: runner, TEST_FAKE_TOOLS: fakeTools, TEST_FIXTURE: fixture,
      APP_JWT_SECRET: Buffer.alloc(32, 1).toString('base64'), M1_TEST_CITIZEN1_PASSWORD: 'synthetic',
      M1_TEST_CITIZEN2_PASSWORD: 'synthetic', M1_TEST_EMPLOYEE_PASSWORD: 'synthetic',
      M2_LAB_CERT_FILE: join(fixture, 'cert.pem'), M2_LAB_KEY_FILE: join(fixture, 'key.pem') },
    windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']
  });
  children.add(child); let output = ''; child.stdout.on('data', part => { output += part; }); child.stderr.resume();
  assert.equal(await exited(child), 0, output);
  assert.match(output, /OCCUPIED_PORT_REJECTED/);
  assert.equal(backend.listening, true);
});

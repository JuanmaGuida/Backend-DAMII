// Local HTTPS same-origin lab. No access logging, external requests or TLS bypasses.
import { createServer } from 'node:https';
import { request } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
import process from 'node:process';
import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const required = ['M2_LAB_CERT_FILE', 'M2_LAB_KEY_FILE', 'M2_LAB_FRONTEND_DIST'];
if (required.some(name => !process.env[name])) throw new Error('Local HTTPS certificate, key and frontend dist required');
const root = resolve(process.env.M2_LAB_FRONTEND_DIST);
const port = Number(process.env.M2_LAB_HTTPS_PORT);
const backendPort = Number(process.env.M2_LAB_BACKEND_PORT);
const runId = process.env.M2_LAB_RUN_ID || randomUUID();
const backendPid = Number(process.env.M2_LAB_BACKEND_PID);
const backendAlive = () => {
  try { process.kill(backendPid, 0); return true; } catch { return false; }
};
if (process.env.M2_LAB_VERIFY_SESSIONS === 'YES' && (!Number.isInteger(backendPid) || backendPid <= 0 || !backendAlive())) {
  throw new Error('The backend started by this lab must be running before verification');
}
let verifier;
if (![port, backendPort].every(p => Number.isInteger(p) && p >= 1024 && p <= 65535)) throw new Error('Invalid loopback ports');
// The PowerShell runner builds explicitly with VITE_IDENTITY_PROVIDER=M1.
const html = await readFile(resolve(root, 'index.html'), 'utf8');
if (!html.includes('/assets/')) throw new Error('Build the frontend before serving the lab');
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.woff2': 'font/woff2' };
// Diagnostics include only an event and a bounded error code, never headers, URLs or bodies.
const diagnostic = (event, error) => console.error(JSON.stringify({ component: 'M1_LAB_PROXY', event,
  code: /^[A-Z0-9_]+$/.test(error?.code || '') ? error.code : 'IO_ERROR' }));
const server = createServer({ cert: await readFile(process.env.M2_LAB_CERT_FILE), key: await readFile(process.env.M2_LAB_KEY_FILE) }, async (req, res) => {
  // Non-secret per-start identity. A stale proxy/catalog 200 cannot satisfy session-check readiness.
  res.setHeader('X-M2-Lab-Run', runId);
  if (req.headers.host !== `localhost:${port}`) { res.writeHead(403).end(); return; }
  if (req.url === '/api' || req.url.startsWith('/api/')) {
    if (backendPid > 0 && !backendAlive()) {
      res.writeHead(503, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      res.end('{"code":"LAB_BACKEND_UNAVAILABLE","message":"Backend local no disponible"}');
      return;
    }
    let upstreamResponse;
    let failed = false;
    let cancelled = false;
    const fail = (error) => {
      if (failed || cancelled) return;
      failed = true;
      diagnostic('upstream_failure', error);
      if (res.destroyed || res.writableEnded) return;
      // A partial response cannot become a successful JSON response or receive a second set of headers.
      if (res.headersSent) res.destroy();
      else {
        res.writeHead(503, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
        res.end('{"code":"LAB_BACKEND_UNAVAILABLE","message":"Backend local no disponible"}');
      }
    };
    const upstream = request({ hostname: '127.0.0.1', port: backendPort, method: req.method, path: req.url,
      headers: { ...req.headers, 'x-forwarded-proto': 'https' }, timeout: 45000 }, response => {
      upstreamResponse = response;
      response.on('error', fail);
      if (cancelled || res.destroyed) { response.destroy(); return; }
      const headers = { ...response.headers };
      delete headers['x-m2-lab-run'];
      res.writeHead(response.statusCode, headers);
      response.pipe(res);
    });
    const cancel = () => {
      cancelled = true;
      upstreamResponse?.destroy();
      upstream.destroy();
    };
    upstream.on('timeout', () => upstream.destroy(Object.assign(new Error('Upstream timeout'), { code: 'ETIMEDOUT' })));
    upstream.on('error', fail);
    req.on('aborted', cancel);
    req.on('error', cancel);
    res.on('error', cancel);
    res.on('close', () => { if (!res.writableFinished) cancel(); });
    req.pipe(upstream);
    return;
  }
  if (!['GET', 'HEAD'].includes(req.method)) { res.writeHead(405).end(); return; }
  try {
    const path = decodeURIComponent(new URL(req.url, `https://localhost:${port}`).pathname);
    let file = resolve(root, `.${path}`);
    if ((file !== root && !file.startsWith(root + sep)) || path.split('/').some(part => part.startsWith('.'))) { res.writeHead(403).end(); return; }
    const info = await stat(file).catch(() => null);
    if (!info?.isFile()) {
      if (extname(path)) { res.writeHead(404).end(); return; }
      file = resolve(root, 'index.html');
    }
    const data = await readFile(file);
    res.writeHead(200, { 'Content-Type': types[extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    res.end(req.method === 'HEAD' ? undefined : data);
  } catch { res.writeHead(404).end(); }
});
// IPv4 loopback only; never expose the lab on a LAN interface.
server.listen(port, '127.0.0.1', () => {
  console.log(`M1 lab HTTPS ready at https://localhost:${port}`);
  if (process.env.M2_LAB_VERIFY_SESSIONS === 'YES') {
    // This callback never runs on EADDRINUSE or startup failure. The child belongs to this proxy.
    verifier = spawn(process.execPath, [...process.execArgv, fileURLToPath(new URL('./verify-m1-session-cycles.mjs', import.meta.url))], {
      env: { ...process.env, M2_SESSION_CHECK_RUN_ID: runId }, windowsHide: true, stdio: ['ignore', 'inherit', 'inherit']
    });
    verifier.on('error', error => { diagnostic('verifier_start_failed', error); process.exitCode = 1; stop(); });
    verifier.on('exit', (code, signal) => {
      if (!stopping && (code !== 0 || signal)) {
        diagnostic('verifier_failed', { code: 'SESSION_CHECK_FAILED' }); process.exitCode = 1; stop();
      }
    });
  }
});
let stopping = false;
const stop = () => {
  stopping = true;
  if (verifier && verifier.exitCode === null && verifier.signalCode === null) verifier.kill();
  server.close(); server.closeAllConnections();
};
process.on('exit', () => { if (verifier && verifier.exitCode === null && verifier.signalCode === null) verifier.kill(); });
process.on('SIGINT', stop);
process.on('SIGTERM', stop);

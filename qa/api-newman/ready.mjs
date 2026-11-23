import { setTimeout as delay } from 'node:timers/promises';

const base = new URL(process.argv[2]);
if (base.origin !== base.href.slice(0, -1) || base.protocol !== 'http:' || base.hostname !== '127.0.0.1' || !base.port) {
  throw new Error('Expected a loopback HTTP origin with an explicit port');
}
const deadline = Date.now() + 90_000;
const allowed = new Set(['application/vnd.spring-boot.actuator.v3+json', 'application/json']);
let health = { httpStatus: null, contentType: null, up: false };
while (Date.now() < deadline) {
  try {
    const response = await fetch(new URL('/actuator/health', base), { redirect: 'error', signal: AbortSignal.timeout(2_000) });
    const contentType = response.headers.get('content-type')?.split(';', 1)[0].trim().toLowerCase() ?? null;
    health = { httpStatus: response.status, contentType, up: false };
    if (response.status === 200 && allowed.has(contentType)) health.up = (await response.json()).status === 'UP';
    if (health.up) break;
  } catch (error) {
    // During startup the port may not exist or Actuator may not yet return a JSON document.
    if (!(error instanceof TypeError || error instanceof SyntaxError || error.name === 'TimeoutError')) throw error;
  }
  await delay(1_000);
}
console.log(JSON.stringify(health));
if (!health.up) process.exitCode = 1;

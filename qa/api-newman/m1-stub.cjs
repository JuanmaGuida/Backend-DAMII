const assert = require('node:assert/strict');
const { createHmac, randomBytes } = require('node:crypto');
const { readFileSync, writeFileSync } = require('node:fs');
const { createServer } = require('node:https');
const path = require('node:path');

const directory = process.argv[2];
const signKey = Buffer.from(process.env.APP_JWT_SECRET ?? '', 'base64');
const accountCredential = process.env.M1_QA_FIXTURE_CREDENTIAL;
assert(signKey.length >= 32 && accountCredential && directory, 'Missing local M1 fixtures');

// Same claims and response shapes as M1TestTokens and M1IdentityProviderTest.
const actorId = 'd04cbd7f-1952-47ae-81b9-977654f70101';
const encode = object => Buffer.from(JSON.stringify(object)).toString('base64url');
let refresh;
function credentials() {
  const now = Math.floor(Date.now() / 1000);
  const payload = encode({ iss: 'modulo1-ciudadanos', sub: '14', typ: 'access', iat: now, exp: now + 900,
    actorId, citizenId: actorId, actorType: 'CIUDADANO', sub_type: 'CIUDADANO', rol: 'ADMINISTRATIVO' });
  const unsigned = encode({ alg: 'HS256', typ: 'JWT' }) + '.' + payload;
  const signature = createHmac('sha256', signKey).update(unsigned).digest('base64url');
  refresh = randomBytes(24).toString('base64url');
  return { Authorization: 'Bearer ' + unsigned + '.' + signature, 'X-Refresh-Token': refresh, 'X-Token-Expires-In': '900' };
}

const server = createServer({
  key: readFileSync(path.join(directory, 'key.pem')), cert: readFileSync(path.join(directory, 'cert.pem'))
}, async (request, response) => {
  try {
    if (request.method !== 'POST' || !['/auth/usuario/login', '/auth/refresh'].includes(request.url)) {
      response.writeHead(404).end();
      return;
    }
    let text = '';
    for await (const data of request) {
      text += data;
      if (text.length > 8192) { response.writeHead(400).end(); return; }
    }
    const body = JSON.parse(text);
    if (request.url === '/auth/usuario/login' && body.gmail === 'session-qa@example.test' && body.password === accountCredential) {
      response.writeHead(200, { ...credentials(), 'Content-Type': 'application/json' }).end(JSON.stringify({
        auth: true, actorId, citizenId: actorId, uuid: actorId, actorType: 'CIUDADANO', user_id: 14,
        nombre: 'Sesión', apellido: 'QA'
      }));
    } else if (request.url === '/auth/refresh' && refresh && body.refreshToken === refresh) {
      response.writeHead(204, credentials()).end();
    } else response.writeHead(401).end();
  } catch {
    response.writeHead(400).end();
  }
});
server.listen(0, '127.0.0.1', () => writeFileSync(path.join(directory, 'port'), String(server.address().port)));

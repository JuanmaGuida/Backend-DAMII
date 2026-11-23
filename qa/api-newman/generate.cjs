const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { operations, spec, bearerRequired, auditContract } = require('./openapi.cjs');
const scenarios = require('./scenarios.cjs');

function item({ id, method, url, status, auth, accept, body, profile, formdata, numericVariables = [], headers = [], checks = [] }) {
  const header = [{ key: 'Accept', value: accept ?? 'application/json, application/problem+json' }, ...headers];
  if (auth) header.push({ key: 'Authorization', value: auth });
  if (body) header.push({ key: 'Content-Type', value: 'application/json' });
  const request = { method, header, url: '{{baseUrl}}' + url };
  if (body) {
    let raw = JSON.stringify(body);
    for (const key of numericVariables) raw = raw.replaceAll('"{{' + key + '}}"', '{{' + key + '}}');
    request.body = { mode: 'raw', raw, options: { raw: { language: 'json' } } };
  }
  if (formdata) request.body = { mode: 'formdata', formdata };
  return {
    name: id, request,
    ...(profile === 'm1' ? { protocolProfileBehavior: { disableCookies: true } } : {}),
    event: [{ listen: 'test', script: { type: 'text/javascript', exec: [
      'pm.test("expected HTTP status", () => pm.response.to.have.status(' + status + '));', ...checks
    ] } }]
  };
}

function authFor(route, operation) {
  if (['/api/auth/refresh', '/api/auth/logout'].includes(route)) return 'Cookie M1 + Origin + CSRF';
  if (route.startsWith('/api/tracking/')) return 'Credenciales de seguimiento';
  if (bearerRequired(operation)) return 'Bearer';
  return (operation.security ?? spec.security ?? []).some(entry => Object.hasOwn(entry, 'bearerAuth'))
    ? 'Bearer opcional' : 'Pública';
}

function defaultFixture(route) {
  if (['/api/auth/refresh', '/api/auth/logout'].includes(route)) return 'M1 local simulado';
  if (route.startsWith('/api/auth/')) return 'Cuenta MOCK';
  if (route.startsWith('/api/tracking/')) return 'Reclamo anónimo en el estado requerido';
  if (route.includes('attachments')) return 'Reclamo y adjunto del dueño';
  if (route.startsWith('/api/catalog/')) return 'Catálogo Flyway';
  if (route.startsWith('/api/admin/catalog/')) return 'Catálogo QA y rol ADMIN';
  if (route.startsWith('/api/indicators/')) return 'Reclamos sintéticos y rol habilitado';
  if (route.includes('/labels')) return 'Etiqueta QA y rol habilitado';
  if (route.startsWith('/api/ai/')) return 'Entrada válida e IA desactivada';
  if (route.includes('/tickets') || route.includes('/tracking-code')) return 'Reclamo en el estado requerido y rol habilitado';
  return 'Cuenta MOCK con el rol requerido';
}

function renderMatrix() {
  const table = operations.map(({ method, route, operation }) => {
    const cases = scenarios.filter(scenario => scenario.method === method && scenario.route === route);
    const positive = cases.filter(({ status }) => status < 300);
    const negative = cases.filter(({ status }) => status >= 400);
    const response = Object.keys(operation.responses);
    const plannedError = bearerRequired(operation) ? '401 sin token' : response.find(code => /^[45]/.test(code));
    const descriptions = list => list.map(({ id, status }) => id + ' (' + status + ')').join(', ');
    const plannedSuccess = response.find(code => code.startsWith('2'));
    return [
      operation.operationId, method, route, authFor(route, operation),
      descriptions(positive) || plannedSuccess + ' con datos válidos',
      descriptions(negative) || plannedError + ' por preparar',
      cases.length ? [...new Set(cases.map(({ fixture }) => fixture))].join(', ') : defaultFixture(route),
      positive.length && negative.length ? 'Preparado +/−' : cases.length ? 'Preparado parcial' : 'Pendiente'
    ].join(' | ');
  });
  return [
    '# Matriz OpenAPI de M2', '',
    'Inventario generado desde el contrato versionado. Los casos preparados describen el alcance de la colección. La evidencia se registra en coverage-executed.md después de validar las respuestas.', '',
    'El endpoint de salud pertenece al arranque. Refresh y logout corren con M1 simulado en loopback.', '',
    '| Operación | Método | Ruta | Autenticación | Éxito | Error | Fixture | Cobertura |',
    '| --- | --- | --- | --- | --- | --- | --- | --- |',
    ...table.map(row => '| ' + row + ' |'), ''
  ].join('\n');
}

assert.equal(new Set(scenarios.map(item => item.id)).size, scenarios.length, 'Duplicate scenario ID');
const problems = auditContract();
assert.equal(problems.length, 0, problems.join('\n'));
for (const scenario of scenarios) {
  const operation = spec.paths[scenario.route]?.[scenario.method.toLowerCase()];
  assert(operation?.responses?.[scenario.status], 'Scenario not documented: ' + scenario.id);
}

const schema = 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json';
const artifacts = { 'coverage.md': renderMatrix() };
for (const profile of ['mock', 'm1']) {
  const collection = {
    info: { name: 'M2 API y OpenAPI ' + profile, schema },
    item: scenarios.filter(entry => (entry.profile ?? 'mock') === profile).map(item)
  };
  artifacts[profile + '.postman_collection.json'] = JSON.stringify(collection, null, 2) + '\n';
}
for (const [filename, value] of Object.entries(artifacts)) {
  const file = path.join(__dirname, filename);
  if (process.argv.includes('--check')) assert.equal(fs.readFileSync(file, 'utf8').replaceAll('\r\n', '\n'), value, filename + ' is stale');
  else fs.writeFileSync(file, value);
}
console.log(operations.length + ' operations, ' + scenarios.length + ' requests');

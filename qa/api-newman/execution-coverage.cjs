const assert = require('node:assert/strict');
const { operations } = require('./openapi.cjs');

function operationCoverage(requests) {
  const passed = requests.filter(item => item.pass);
  return operations.map(({ method, route, operation }) => {
    const matches = passed.filter(item => item.method === method && item.route === route);
    return {
      operationId: operation.operationId,
      method,
      route,
      successes: matches.filter(item => item.observed >= 200 && item.observed < 300).map(item => item.id),
      errors: matches.filter(item => item.observed >= 400 && item.observed < 600).map(item => item.id)
    };
  });
}

function writeCoverage(result) {
  const evidence = result.coverage;
  const full = evidence.filter(row => row.successes.length && row.errors.length).length;
  const rows = evidence.map(row => '| ' + [
    row.operationId, row.method, row.route, row.successes.join(', ') || 'Pendiente', row.errors.join(', ') || 'Pendiente',
    row.successes.length && row.errors.length ? 'Acreditado' : 'Pendiente'
  ].join(' | ') + ' |');
  assert(evidence.length > 0);
  return [
    '# Cobertura ejecutada de M2', '',
    'Revisión base ' + result.revision,
    'SHA256 de OpenAPI ' + result.openapiSha256, '',
    full + ' de ' + evidence.length + ' operaciones con un éxito y un error validados en esta corrida.', '',
    '| Operación | Método | Ruta | Éxitos verificados | Errores verificados | Estado |',
    '| --- | --- | --- | --- | --- | --- |',
    ...rows, ''
  ].join('\n');
}

module.exports = { operationCoverage, writeCoverage };

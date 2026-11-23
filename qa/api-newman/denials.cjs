const { operations, spec, resolve, bearerRequired } = require('./openapi.cjs');

module.exports = (scenarios) => operations.flatMap(({ method, route, operation }) => {
  if (!bearerRequired(operation) || scenarios.some(item =>
    item.method === method && item.route === route && item.status >= 400)) return [];
  const parameters = [...(spec.paths[route].parameters ?? []), ...(operation.parameters ?? [])].map(resolve);
  const url = route.replace(/\{(\w+)\}/g, (_, key) => {
    const parameter = parameters.find(entry => entry.in === 'path' && entry.name === key);
    return resolve(parameter?.schema)?.format === 'uuid' ? '00000000-0000-4000-8000-000000000001' : '1';
  });
  return [{ id: 'without-bearer-' + operation.operationId, method, route, url, status: 401, fixture: 'Sin sesión Bearer' }];
});

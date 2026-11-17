import fs from 'node:fs';
import path from 'node:path';
import yaml from 'js-yaml';

const root = path.resolve(import.meta.dirname, '../..');
const specPath = path.join(root, 'src/main/resources/static/openapi.yaml');
const spec = yaml.load(fs.readFileSync(specPath, 'utf8'));
const methods = new Set(['get', 'post', 'put', 'patch', 'delete', 'head', 'options']);
function dereference(value) {
  let current = value;
  const visited = new Set();
  while (current && typeof current === 'object' && typeof current.$ref === 'string' && current.$ref.startsWith('#/')) {
    if (visited.has(current.$ref)) return null;
    visited.add(current.$ref);
    current = current.$ref.slice(2).split('/').reduce((node, key) => node?.[key.replaceAll('~1', '/').replaceAll('~0', '~')], spec);
  }
  return current;
}
const operations = [];
for (const [route, pathItem] of Object.entries(spec.paths ?? {})) {
  for (const [method, operation] of Object.entries(pathItem ?? {})) {
    if (methods.has(method)) operations.push({ route, method: method.toUpperCase(), operationId: operation.operationId ?? '', operation });
  }
}
const gaps = operations.filter(({ operation }) => {
  const security = operation.security ?? spec.security ?? [];
  const protectedOperation = Array.isArray(security)
    && security.some((alternative) => Object.hasOwn(alternative ?? {}, 'bearerAuth'))
    && !security.some((alternative) => Object.keys(alternative ?? {}).length === 0);
  if (!protectedOperation) return false;
  const response = dereference(operation.responses?.['401']);
  return !response || !response.content?.['application/json']?.schema;
}).map(({ route, method, operationId }) => ({ route, method, operationId, response: '401', missing: 'application/json schema' }));

const summary = {
  spec: path.relative(root, specPath),
  openapi: spec.openapi,
  operationCount: operations.length,
  routeCount: Object.keys(spec.paths ?? {}).length,
  bearerProtectedOperationCount: operations.filter(({ operation }) => {
    const security = operation.security ?? spec.security ?? [];
    return Array.isArray(security) && security.some((alternative) => Object.hasOwn(alternative ?? {}, 'bearerAuth'))
      && !security.some((alternative) => Object.keys(alternative ?? {}).length === 0);
  }).length,
  unauthorizedSchemaGapCount: gaps.length,
  gaps
};
const outputIndex = process.argv.indexOf('--output');
const outputPath = outputIndex >= 0 ? process.argv[outputIndex + 1] : null;
if (outputPath) fs.writeFileSync(outputPath, `${JSON.stringify(summary, null, 2)}\n`);
process.stdout.write(`${JSON.stringify(summary, null, 2)}\n`);
if (process.argv.includes('--fail-on-gaps') && gaps.length > 0) process.exitCode = 1;

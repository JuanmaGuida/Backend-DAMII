const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const { load } = require('js-yaml');
const Ajv = require('ajv');
const addFormats = require('ajv-formats');

const specFile = path.resolve(__dirname, '../../src/main/resources/static/openapi.yaml');
const bytes = fs.readFileSync(specFile);
const spec = load(bytes.toString('utf8'));
const sha256 = createHash('sha256').update(bytes).digest('hex');
const methods = new Set(['get', 'post', 'put', 'patch', 'delete', 'head', 'options', 'trace']);
const operations = Object.entries(spec.paths).flatMap(([route, item]) =>
  Object.entries(item).filter(([method]) => methods.has(method))
    .map(([method, operation]) => ({ method: method.toUpperCase(), route, operation })));

function resolve(value) {
  const seen = new Set();
  while (value?.$ref) {
    const reference = value.$ref;
    assert(reference.startsWith('#/') && !seen.has(reference), 'Expected a local OpenAPI reference');
    seen.add(reference);
    value = reference.slice(2).split('/').reduce(
      (node, segment) => node?.[segment.replaceAll('~1', '/').replaceAll('~0', '~')], spec);
  }
  return value;
}

function bearerRequired(operation) {
  const security = operation.security ?? spec.security ?? [];
  return security.some(item => Object.hasOwn(item, 'bearerAuth')) && !security.some(item => !Object.keys(item).length);
}

function auditContract() {
  const problems = [];
  for (const { method, route, operation } of operations) {
    const label = method + ' ' + route;
    const codes = Object.keys(operation.responses);
    if (!codes.some(code => code.startsWith('2'))) problems.push(label + ' has no documented success');
    if (!codes.some(code => /^[45]/.test(code))) problems.push(label + ' has no documented error');
    if (bearerRequired(operation) && !codes.includes('401')) problems.push(label + ' lacks 401');
    for (const [code, definition] of Object.entries(operation.responses)) {
      const content = resolve(definition)?.content ?? {};
      const schema = resolve(content['application/problem+json']?.schema);
      for (const media of ['application/json', 'application/problem+json']) {
        if (!content[media]) continue;
        try {
          assert(content[media].schema);
          validatorFor(content[media].schema);
        } catch {
          problems.push(label + ' ' + code + ' has an invalid JSON response schema');
        }
      }
      if (/^[45]/.test(code) && !['type', 'title', 'status', 'detail', 'code', 'message']
        .every(key => schema?.required?.includes(key))) problems.push(label + ' ' + code + ' lacks Problem Details');
    }
  }
  return problems;
}

const ajv = addFormats(new Ajv({ strictTypes: false }));
ajv.addKeyword('components');
ajv.addKeyword('example');
ajv.addFormat('password', true);
ajv.addFormat('int32', { type: 'number', validate: n => Number.isInteger(n) && n >= -2147483648 && n <= 2147483647 });
ajv.addFormat('int64', { type: 'number', validate: Number.isInteger });
ajv.addFormat('double', { type: 'number', validate: Number.isFinite });
const validators = new WeakMap();

function validatorFor(schema) {
  if (!validators.has(schema)) {
    // Keep nested local OpenAPI references available to Ajv.
    validators.set(schema, ajv.compile({ ...schema, components: spec.components }));
  }
  return validators.get(schema);
}

function checkResponse(scenario, response) {
  assert.equal(response.code, scenario.status, 'Unexpected HTTP status');
  const operation = spec.paths[scenario.route]?.[scenario.method.toLowerCase()];
  const declared = resolve(operation?.responses?.[scenario.status]);
  assert(declared, 'Status missing from OpenAPI');
  const media = response.headers.get('Content-Type')?.split(';', 1)[0].trim().toLowerCase();
  if (scenario.status === 204) {
    assert(!declared.content && !response.stream?.length, '204 must be bodyless');
    return;
  }
  const definition = declared.content?.[media];
  assert(definition?.schema, 'Unexpected or undocumented Content-Type');
  if (media === 'application/problem+json' || media === 'application/json') {
    const body = response.json();
    if (media === 'application/problem+json') assert.equal(body.status, response.code, 'Problem Details status mismatch');
    const validate = validatorFor(definition.schema);
    if (!validate(body)) {
      const rule = validate.errors[0]?.schemaPath;
      assert.fail('Body does not conform to OpenAPI' + (rule ? ' (' + rule + ')' : ''));
    }
  } else if (media === 'text/csv' || definition.schema.format === 'binary') {
    assert(response.stream?.length, 'Expected a non-empty file response');
    if (scenario.binaryFixture) {
      const fixture = fs.readFileSync(path.resolve(__dirname, '../..', scenario.binaryFixture));
      assert(response.stream.equals(fixture), 'Downloaded bytes differ from the uploaded fixture');
    }
  } else {
    assert.fail('Unsupported response validation media type');
  }
}

module.exports = { operations, spec, sha256, resolve, bearerRequired, auditContract, checkResponse };

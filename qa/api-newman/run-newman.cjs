const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { createHash } = require('node:crypto');
const { registerHooks } = require('node:module');
const { pathToFileURL } = require('node:url');
const assert = require('node:assert/strict');
const { sha256, checkResponse } = require('./openapi.cjs');
const { operationCoverage, writeCoverage } = require('./execution-coverage.cjs');

const [origin, output, profile = 'mock'] = process.argv.slice(2);
assert(['mock', 'm1'].includes(profile), 'Unsupported QA identity fixture');
const scenarios = require('./scenarios.cjs').filter(item => (item.profile ?? 'mock') === profile);
const xml = value => String(value).replace(/[&<>'"]/g, char =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&apos;', '"': '&quot;' })[char]);

async function run() {
  const base = new URL(origin);
  assert(base.href === base.origin + '/' && base.protocol === 'http:' && base.hostname === '127.0.0.1' && base.port,
    'Expected the disposable backend on loopback');
  const revision = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: __dirname, encoding: 'utf8' }).trim();
  const collectionText = fs.readFileSync(path.join(__dirname, (profile + '.postman_collection.json')), 'utf8');
  assert(!/\{\{\s*\$/.test(collectionText), 'Use deterministic fixture variables in the QA collection');
    const collectionSha256 = createHash('sha256').update(collectionText).digest('hex');
  const collection = JSON.parse(collectionText);
  assert(collection.item.every((entry, index) =>
    entry.name === scenarios[index]?.id && entry.request.url.startsWith('{{baseUrl}}/api/')),
  'Unexpected scenario or non-local request');
  const passwordAdmin = process.env.MOCK_ADMIN_PASSWORD;
  const passwordCitizen = process.env.MOCK_CITIZEN_PASSWORD;
  const passwordArea = process.env.MOCK_AREA_RESPONSIBLE_PASSWORD;
  const passwordAgent = process.env.MOCK_AGENT_PASSWORD;
  const identity = profile === 'mock' ? { passwordAdmin, passwordCitizen, passwordArea, passwordAgent } :
    { m1FixtureCredential: process.env.M1_QA_FIXTURE_CREDENTIAL };
  assert(Object.values(identity).every(Boolean), 'Missing synthetic identity fixtures');

  // The SDK uses Faker 5's removed API for random substitutions. This collection uses
  // explicit fixtures. Replace only the SDK's random generators, with no changes to dependencies on disk.
  const dynamicGenerators = pathToFileURL(require.resolve('postman-collection/lib/superstring/dynamic-variables')).href;
  const deterministicOnly = registerHooks({
    load(url, context, nextLoad) {
      if (url === dynamicGenerators) {
        return { format: 'commonjs', shortCircuit: true, source: 'module.exports = Object.freeze({});' };
      }
      return nextLoad(url, context);
    }
  });
  const started = Date.now();
  let summary;
  try {
    const published = await fetch(new URL('/openapi.yaml', base), { redirect: 'error', signal: AbortSignal.timeout(5_000) });
    assert.equal(published.status, 200, 'Published OpenAPI unavailable');
    assert.equal(createHash('sha256').update(Buffer.from(await published.arrayBuffer())).digest('hex'), sha256,
      'The running JAR does not publish the checkout OpenAPI');
    const newman = require('newman');
    summary = await new Promise((resolve, reject) => {
      newman.run({
        collection, envVar: Object.entries({ baseUrl: base.origin, ...identity })
          .map(([key, value]) => ({ key, value })),
        workingDir: path.resolve(__dirname, '../..'), insecureFileRead: false,
        reporters: [], ignoreRedirects: true, timeout: 120_000, timeoutRequest: 8_000, timeoutScript: 2_000
      }, (error, value) => error ? reject(error) : resolve(value));
    });
  } finally {
    deterministicOnly.deregister();
  }

  const executions = new Map(summary.run.executions.map(entry => [entry.item.name, entry]));
  const failedRequests = new Set(summary.run.failures.map(failure => failure.source?.name));
  const requests = scenarios.map(scenario => {
    const { id, method, route, status } = scenario;
    const response = executions.get(id)?.response;
    const result = {
      id, method, route, expected: status, observed: response?.code ?? null,
      contentType: response?.headers.get('Content-Type')?.split(';', 1)[0] ?? null
    };
    if (!response) return { ...result, pass: false, reason: 'No HTTP response' };
    if (failedRequests.has(id)) return { ...result, pass: false, reason: 'Newman assertion or request failed' };
    try {
      checkResponse(scenario, response);
      return { ...result, pass: true };
    } catch (error) {
      // Schema validation errors can contain body fragments. Emit only our fixed assertion labels.
      return { ...result, pass: false, reason: error.code === 'ERR_ASSERTION' ? error.message : 'Could not validate response schema' };
    }
  });
  if (summary.run.failures.some(failure => !executions.has(failure.source?.name))) {
    requests.push({ id: 'newman-runtime', pass: false, reason: 'Newman reported an unassociated failure' });
  }
  const assertions = summary.run.stats.assertions;
  if (summary.run.executions.length !== scenarios.length || assertions.total < scenarios.length || assertions.failed) {
    requests.push({ id: 'collection-coverage', pass: false, reason: 'Incomplete execution or failed Newman assertions' });
  }
  const failures = requests.filter(request => !request.pass).length;
  const result = {
    revision, openapiSha256: sha256, collectionSha256, profile, status: failures ? 'FAIL' : 'PASS',
    requestsExecuted: summary.run.executions.length, assertions: assertions.total,
    durationMs: Date.now() - started, coverage: operationCoverage(requests), requests
  };
  fs.mkdirSync(output, { recursive: true });
  fs.writeFileSync(path.join(output, 'newman-' + profile + '.json'), JSON.stringify(result, null, 2) + '\n');
  fs.writeFileSync(path.join(output, 'coverage-' + profile + '.md'), writeCoverage(result));
  const suite = requests.map(({ id, pass, reason }) => '  <testcase classname="api-newman" name="' + xml(id) + '">' +
    (pass ? '' : '<failure message="' + xml(reason) + '"/>') + '</testcase>');
  fs.writeFileSync(path.join(output, 'newman-' + profile + '.xml'),
    '<?xml version="1.0" encoding="utf-8"?>\n<testsuite name="api-newman" tests="' + requests.length +
    '" failures="' + failures + '">\n' + suite.join('\n') + '\n</testsuite>\n');
  console.log(profile + ': ' + result.status + ' — ' + result.requestsExecuted + ' HTTP responses, ' + result.assertions + ' Newman assertions');
  if (failures) process.exitCode = 1;
}

run().catch(() => {
  console.error('Newman could not complete setup or HTTP execution.');
  process.exitCode = 1;
});

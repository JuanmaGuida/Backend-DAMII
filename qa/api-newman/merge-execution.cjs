const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { operationCoverage, writeCoverage } = require('./execution-coverage.cjs');

const directory = process.argv[2];
const reportFor = profile => JSON.parse(fs.readFileSync(path.join(directory, 'newman-' + profile + '.json'), 'utf8'));
const mock = reportFor('mock');
const m1 = reportFor('m1');
assert.equal(mock.revision, m1.revision);
assert.equal(mock.openapiSha256, m1.openapiSha256);
const requests = [...mock.requests, ...m1.requests];
const coverage = operationCoverage(requests);
const coveredOperations = coverage.filter(row => row.successes.length && row.errors.length).length;
const full = coveredOperations === coverage.length;
const { checks } = JSON.parse(fs.readFileSync(path.join(directory, 'effects.json'), 'utf8'));
const validEffects = Array.isArray(checks) && checks.length > 0 && checks.every(check => check.pass === true);
const summary = {
  revision: mock.revision, openapiSha256: mock.openapiSha256,
  jarSha256: fs.readFileSync(path.join(directory, 'jar-sha256.txt'), 'utf8').trim(),
  collectionSha256: { mock: mock.collectionSha256, m1: m1.collectionSha256 },
  suiteWallSeconds: Number(fs.readFileSync(path.join(directory, 'suite-wall-seconds.txt'), 'utf8').trim()),
  status: full && validEffects && mock.status === 'PASS' && m1.status === 'PASS' ? 'PASS' : 'FAIL',
  databaseChecksPassed: validEffects,
  publishedOperations: coverage.length, coveredOperations,
  requestsExecuted: mock.requestsExecuted + m1.requestsExecuted,
  assertions: mock.assertions + m1.assertions,
  durationMs: mock.durationMs + m1.durationMs,
  coverage,
  requests
};
fs.writeFileSync(path.join(directory, 'newman.json'), JSON.stringify(summary, null, 2) + '\n');
fs.writeFileSync(path.join(directory, 'coverage-executed.md'), writeCoverage(summary));
console.log(coveredOperations + ' of ' + coverage.length + ' published operations covered in both directions');
if (summary.status !== 'PASS') process.exitCode = 1;

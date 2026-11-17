const fs = require('node:fs');
const path = require('node:path');
const newman = require('newman');

const collectionArgument = process.argv[2] || 'collections/anonymous.postman_collection.json';
const cwdCollectionPath = path.resolve(process.cwd(), collectionArgument);
const collectionPath = fs.existsSync(cwdCollectionPath)
  ? cwdCollectionPath
  : path.resolve(__dirname, collectionArgument);
const summaryArg = process.argv.indexOf('--summary');
const summaryPath = summaryArg >= 0 ? path.resolve(process.argv[summaryArg + 1]) : null;
const variables = process.argv.slice(3).filter((arg) => !arg.startsWith('--') && arg.includes('='));
const envVars = variables.map((arg) => {
  const separator = arg.indexOf('=');
  return { key: arg.slice(0, separator), value: arg.slice(separator + 1), type: 'default', enabled: true };
});
const collection = JSON.parse(fs.readFileSync(collectionPath, 'utf8'));

newman.run({ collection, envVar: envVars, reporters: [] }, (error, result) => {
  if (!result) {
    process.stderr.write('Newman did not return a run summary.\n');
    process.exitCode = 1;
    return;
  }
  const failures = (result.run.failures ?? []).map((failure) => ({
    request: failure.source?.name ?? 'unknown',
    assertion: failure.error?.name ?? 'assertion failure'
  }));
  const safe = {
    collection: collection.info?.name ?? path.basename(collectionPath),
    status: error || failures.length ? 'FAIL' : 'PASS',
    requestCount: result.run.stats?.requests?.total ?? 0,
    assertionCount: result.run.stats?.assertions?.total ?? 0,
    assertionsFailed: result.run.stats?.assertions?.failed ?? 0,
    durationMs: result.run.timings?.completed && result.run.timings?.started
      ? result.run.timings.completed - result.run.timings.started : null,
    failures
  };
  const encoded = `${JSON.stringify(safe, null, 2)}\n`;
  if (summaryPath) {
    fs.mkdirSync(path.dirname(summaryPath), { recursive: true });
    fs.writeFileSync(summaryPath, encoded);
  }
  process.stdout.write(encoded);
  if (safe.status !== 'PASS') process.exitCode = 1;
});

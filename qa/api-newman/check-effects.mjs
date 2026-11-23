import assert from 'node:assert/strict';
import fs from 'node:fs';

const [beforeFile, afterFile, outputFile] = process.argv.slice(2);
const read = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const before = read(beforeFile);
const after = read(afterFile);
const checks = [
  { name: 'only one net label inserted', pass: after.labels === before.labels + 1 },
  ...['categories', 'subcategories'].map(name => ({
    name: name + ' grows by one', pass: Number.isInteger(before[name]) && after[name] === before[name] + 1
  })),
  { name: 'two request types persisted', pass: after.request_types === before.request_types + 2 },
  { name: 'pilot label persisted exactly once', pass: before.pilot_label === 0 && after.pilot_label === 1 },
  { name: 'rejected labels absent', pass: before.rejected_labels === 0 && after.rejected_labels === 0 },
  { name: 'ticket creation and replay persisted exactly nine tickets', pass: after.tickets === before.tickets + 9 },
  { name: 'one creation event per identified ticket', pass: after.created_ticket_events === before.created_ticket_events + 6 },
  { name: 'one identified fixture', pass: before.owned_fixture === 0 && after.owned_fixture === 1 },
  { name: 'rejected replay did not persist', pass: before.idempotency_rejected === 0 && after.idempotency_rejected === 0 },
  { name: 'only the two fixture uploads persisted', pass: after.attachments === before.attachments + 2 }
];
fs.writeFileSync(outputFile, JSON.stringify({ checks }, null, 2) + '\n');
assert(checks.every(check => check.pass), 'Unexpected effects in the disposable PostgreSQL fixture');
console.log(checks.length + ' database effects checks passed');

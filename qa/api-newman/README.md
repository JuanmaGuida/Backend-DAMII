# DDA2 API QA probes

The collections are generated from `src/main/resources/static/openapi.yaml` and run only against an isolated backend plus PostgreSQL. They use synthetic mock identities and synthetic fixtures. They do not call Core, M1, Lightsail, or an AI provider.

## Generate and audit the checked-in collection

From the repository root:

```sh
npm ci --prefix qa/api-newman
npm --prefix qa/api-newman run generate
npm --prefix qa/api-newman run audit:openapi
```

The strict CI audit uses `--fail-on-gaps`. It currently exits nonzero for the known eight protected operations whose 401 responses omit an `application/json` schema. Keep that failure visible until the contract is corrected.

## Collection scope

- `collections/anonymous.postman_collection.json`: one assertion-bearing anonymous 401 probe for every required-bearer operation, plus public catalog, unknown-resource, and health smoke cases.
- `collections/role-bfla.postman_collection.json`: synthetic role checks for the admin directory, global indicators, and the area inbox.
- `collections/ownership-bfla.postman_collection.json`: synthetic ticket ownership, area boundary, and lifecycle authorization probes.
- `collections/mass-assignment.postman_collection.json`: one normal ticket creation request containing forbidden root-level administrative fields. CI verifies persistence and the local outbox with `verify-mass-assignment.sql`.

These are focused probes, not complete success/error coverage for all 95 operations or an exhaustive role × ownership × area matrix. Dynamic `formData` stays a valid field and is not treated as an administrative property. Newman summaries include counts, assertion names, status, and duration only; request/response bodies, credentials, and bearer tokens are not written to artifacts.

CI starts a `dev` profile backend with synthetic passwords and a local PostgreSQL service, applies `seed-fixture.sql`, executes the four collections, compares database counts around role-denial probes, checks ownership/lifecycle ticket state and per-ticket effects in `verify-bfla-fixture.sql`, validates the successful mass-assignment request in `verify-mass-assignment.sql`, and runs the strict OpenAPI audit. The CI audit is expected to expose the eight known schema gaps.

The checked-in Newman `6.2.3` dependency tree currently has npm audit advisories, including one critical. This is QA tooling only, but it remains visible to repository security scanning and is not an approved exception or merge signal. Do not suppress or lower SCA checks to accommodate it.

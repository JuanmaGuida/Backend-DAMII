import fs from 'node:fs';
import path from 'node:path';
import yaml from 'js-yaml';

const root = path.resolve(import.meta.dirname, '../..');
const specPath = path.join(root, 'src/main/resources/static/openapi.yaml');
const output = path.join(import.meta.dirname, 'collections');
const spec = yaml.load(fs.readFileSync(specPath, 'utf8'));
const httpMethods = new Set(['get', 'post', 'put', 'patch', 'delete', 'head', 'options']);
const uuid = '00000000-0000-4000-8000-000000000001';

function routeFor(pathTemplate, operation, pathItem) {
  const parameterMap = new Map();
  for (const parameter of [...(pathItem.parameters ?? []), ...(operation.parameters ?? [])]) {
    if (parameter?.in === 'path' && parameter.name) parameterMap.set(parameter.name, parameter.schema ?? {});
  }
  return pathTemplate.replace(/\{([^}]+)\}/g, (_match, name) => {
    const schema = parameterMap.get(name) ?? {};
    if (schema.format === 'uuid' || /uuid|ticketid|actorid|citizenid|eventid/i.test(name)) return uuid;
    if (schema.type === 'integer' || schema.type === 'number' || /id$/i.test(name)) return '1';
    return 'qa-synthetic';
  });
}

function hasRequiredBearer(security) {
  return Array.isArray(security)
    && security.some((alternative) => Object.hasOwn(alternative ?? {}, 'bearerAuth'))
    && !security.some((alternative) => Object.keys(alternative ?? {}).length === 0);
}

function collection(name, requests) {
  return {
    info: {
      name,
      schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json',
      description: `Synthetic QA requests generated from ${path.relative(root, specPath)}. No shared service is called.`
    },
    variable: [{ key: 'baseUrl', value: 'http://127.0.0.1:18080' }],
    item: requests
  };
}

function requestItem(name, method, url, tests, { headers = [], body, prerequest } = {}) {
  const event = [];
  if (prerequest) event.push({ listen: 'prerequest', script: { type: 'text/javascript', exec: prerequest } });
  if (tests?.length) event.push({ listen: 'test', script: { type: 'text/javascript', exec: tests } });
  const request = { method: method.toUpperCase(), header: headers, url: `{{baseUrl}}${url}` };
  if (body !== undefined) request.body = { mode: 'raw', raw: JSON.stringify(body), options: { raw: { language: 'json' } } };
  return { name, request, ...(event.length ? { event } : {}) };
}

function numericBodyVariable(item, name) {
  item.request.body.raw = item.request.body.raw.replace(`"{{${name}}}"`, `{{${name}}}`);
  return item;
}

const anonymous = [];
let protectedCount = 0;
for (const [route, pathItem] of Object.entries(spec.paths ?? {})) {
  for (const [method, operation] of Object.entries(pathItem ?? {})) {
    if (!httpMethods.has(method) || !hasRequiredBearer(operation.security ?? spec.security)) continue;
    protectedCount += 1;
    const url = routeFor(route, operation, pathItem);
    anonymous.push(requestItem(
      `Anonymous 401: ${method.toUpperCase()} ${url} (${operation.operationId ?? 'unnamed'})`,
      method,
      url,
      [
        'pm.test("protected operation rejects anonymous access", () => pm.response.to.have.status(401));',
        'pm.test("401 body is a JSON error object", () => { pm.expect(pm.response.headers.get("Content-Type") || "").to.include("application/json"); const error = pm.response.json(); pm.expect(error).to.have.property("code").that.is.a("string"); pm.expect(error).to.have.property("message").that.is.a("string"); });',
        'pm.test("anonymous response does not set a session cookie", () => pm.expect(pm.response.headers.has("Set-Cookie")).to.eql(false));'
      ]
    ));
  }
}

const smoke = [
  requestItem('Public neighborhood catalog', 'get', '/api/catalog/neighborhoods', [
    'pm.test("public catalog responds successfully", () => pm.response.to.have.status(200));',
    'pm.test("public catalog is JSON", () => pm.expect(pm.response.headers.get("Content-Type") || "").to.include("application/json"));'
  ]),
  requestItem('Public category catalog', 'get', '/api/catalog/categories', [
    'pm.test("public catalog responds successfully", () => pm.response.to.have.status(200));',
    'pm.test("public catalog is JSON", () => pm.expect(pm.response.headers.get("Content-Type") || "").to.include("application/json"));'
  ]),
  requestItem(`Unknown public neighborhood returns 404`, 'get', `/api/catalog/neighborhoods/${uuid}`, [
    'pm.test("unknown public catalog item returns 404", () => pm.response.to.have.status(404));',
    'pm.test("not-found response is a JSON error", () => { const error = pm.response.json(); pm.expect(error).to.have.property("code").that.is.a("string"); pm.expect(error).to.have.property("message").that.is.a("string"); });'
  ]),
  requestItem('Health endpoint', 'get', '/actuator/health', [
    'pm.test("local health endpoint is up", () => pm.response.to.have.status(200));',
    'pm.test("health response is JSON", () => pm.expect(pm.response.headers.get("Content-Type") || "").to.include("application/json"));'
  ])
];

const authRoles = [
  ['Citizen', 'citizen@example.test', '{{citizenPassword}}', 'CITIZEN', 'tokenCitizen'],
  ['Agent', 'agent@example.test', '{{agentPassword}}', 'AGENT', 'tokenAgent'],
  ['Area responsible', 'area.responsible@example.test', '{{areaPassword}}', 'AREA_RESPONSIBLE', 'tokenArea'],
  ['Admin', 'module.admin@example.test', '{{adminPassword}}', 'ADMIN', 'tokenAdmin']
];
const roleRequests = [];
for (const [label, username, password, role, tokenVar] of authRoles) {
  roleRequests.push(requestItem(`Login synthetic ${label}`, 'post', '/api/auth/login', [
    'pm.test("synthetic login succeeds", () => pm.response.to.have.status(200));',
    `pm.test("login identity is ${role}", () => { const body = pm.response.json(); pm.expect(body).to.have.property("identity"); pm.expect(body.identity.role).to.eql("${role}"); });`,
    `pm.environment.set("${tokenVar}", pm.response.json().token);`
  ], { headers: [{ key: 'Content-Type', value: 'application/json' }], body: { username, password } }));
}

function roleProbe(label, route, tokenVar, expectedStatus, method = 'get') {
  roleRequests.push(requestItem(label, method, route, [
    `pm.test("${label} returns ${expectedStatus}", () => pm.response.to.have.status(${expectedStatus}));`
  ], { headers: [{ key: 'Authorization', value: `Bearer {{${tokenVar}}}` }] }));
}
for (const [label, _username, _password, _role, token] of authRoles) {
  roleProbe(`${label} role boundary: admin directory`, '/api/admin/agents', token, label === 'Admin' ? 200 : 403);
}
roleProbe('Citizen cannot read indicator overview', '/api/indicators/overview', 'tokenCitizen', 403);
roleProbe('Agent can read indicator overview', '/api/indicators/overview', 'tokenAgent', 200);
roleProbe('Area responsible cannot read global indicator overview', '/api/indicators/overview', 'tokenArea', 403);
roleProbe('Admin can read indicator overview', '/api/indicators/overview', 'tokenAdmin', 200);
roleProbe('Citizen cannot read area inbox', '/api/staff/tickets/area', 'tokenCitizen', 403);
roleProbe('Agent cannot read area inbox', '/api/staff/tickets/area', 'tokenAgent', 403);
roleProbe('Area responsible can read own area inbox', '/api/staff/tickets/area', 'tokenArea', 200);
roleProbe('Admin cannot use role-specific area inbox', '/api/staff/tickets/area', 'tokenAdmin', 403);

const forbiddenBody = {
  requestTypeId: '{{requestTypeId}}',
  summary: '{{qaTicketSummary}}',
  description: 'Synthetic QA fixture for root-level mass assignment.',
  formData: {},
  role: 'ADMIN',
  status: 'RESOLVED',
  currentStatus: 'RESOLVED',
  currentPriority: 'CRITICAL',
  riskScore: 999,
  citizenId: '10000000-0000-0000-0000-000000000006',
  responsibleAreaId: 'M9',
  isPublic: true
};
const citizenLogin = requestItem('Login synthetic Citizen for mass assignment', 'post', '/api/auth/login', [
  'pm.test("synthetic citizen login succeeds", () => pm.response.to.have.status(200));',
  'pm.test("login identity is CITIZEN", () => { const body = pm.response.json(); pm.expect(body.identity.role).to.eql("CITIZEN"); });',
  'pm.environment.set("tokenCitizen", pm.response.json().token);'
], { headers: [{ key: 'Content-Type', value: 'application/json' }], body: { username: 'citizen@example.test', password: '{{citizenPassword}}' } });
const massAssignment = [numericBodyVariable(requestItem(
  'Create normal synthetic ticket with forbidden administrative root fields',
  'post',
  '/api/tickets',
  [
    'pm.test("normal ticket creation still succeeds", () => pm.response.to.have.status(201));',
    'pm.test("response exposes created ticket id", () => { const body = pm.response.json(); pm.expect(body.ticketId || body.id).to.be.ok; pm.environment.set("createdMassAssignmentTicketId", body.ticketId || body.id); });'
  ],
  {
    headers: [
      { key: 'Authorization', value: 'Bearer {{tokenCitizen}}' },
      { key: 'Content-Type', value: 'application/json' }
    ],
    body: forbiddenBody
  }
), 'requestTypeId')];
massAssignment.unshift(citizenLogin);

const ownershipRequests = authRoles.map(([label, username, password, role, tokenVar]) => requestItem(
  `Login synthetic ${label} for ownership probe`, 'post', '/api/auth/login', [
    'pm.test("synthetic login succeeds", () => pm.response.to.have.status(200));',
    `pm.test("login identity is ${role}", () => pm.expect(pm.response.json().identity.role).to.eql("${role}"));`,
    `pm.environment.set("${tokenVar}", pm.response.json().token);`
  ], { headers: [{ key: 'Content-Type', value: 'application/json' }], body: { username, password } }
));

function createOwnedTicket(label, tokenVar, requestTypeVar, ticketVar) {
  return numericBodyVariable(requestItem(`Create ${label}`, 'post', '/api/tickets', [
    'pm.test("synthetic ownership ticket created", () => pm.response.to.have.status(201));',
    `pm.test("${label} response contains ticket id", () => { const body = pm.response.json(); pm.expect(body.ticketId).to.be.a("string"); pm.environment.set("${ticketVar}", body.ticketId); });`
  ], {
    headers: [{ key: 'Authorization', value: `Bearer {{${tokenVar}}}` }, { key: 'Content-Type', value: 'application/json' }],
    body: {
      requestTypeId: `{{${requestTypeVar}}}`,
      summary: `DDA2-QA-BFLA-${label}-{{qaRunTag}}`,
      description: 'Synthetic isolated BFLA ownership fixture.',
      formData: {}
    }
  }), requestTypeVar);
}
ownershipRequests.push(
  createOwnedTicket('agent-created M6 ticket', 'tokenAgent', 'requestTypeIdM6', 'ticketAgentM6'),
  createOwnedTicket('citizen-created M6 ticket', 'tokenCitizen', 'requestTypeIdM6', 'ticketCitizenM6'),
  createOwnedTicket('citizen-created M9 ticket', 'tokenCitizen', 'requestTypeIdM9', 'ticketCitizenM9'),
  createOwnedTicket('area-created M6 ticket', 'tokenArea', 'requestTypeIdM6', 'ticketAreaM6')
);
function ownedProbe(label, route, tokenVar, expectedStatus, method = 'get') {
  ownershipRequests.push(requestItem(label, method, route, [
    `pm.test("${label} returns ${expectedStatus}", () => pm.response.to.have.status(${expectedStatus}));`
  ], { headers: [{ key: 'Authorization', value: `Bearer {{${tokenVar}}}` }] }));
}
ownedProbe('Citizen denied staff detail for own ticket', '/api/staff/tickets/{{ticketCitizenM6}}', 'tokenCitizen', 403);
ownedProbe('Agent reads citizen ticket outside own area', '/api/staff/tickets/{{ticketCitizenM9}}', 'tokenAgent', 200);
ownedProbe('Admin reads citizen ticket outside own area', '/api/staff/tickets/{{ticketCitizenM9}}', 'tokenAdmin', 200);
ownedProbe('Area responsible reads same-area ticket', '/api/staff/tickets/{{ticketCitizenM6}}', 'tokenArea', 200);
ownedProbe('Area responsible denied out-of-area ticket', '/api/staff/tickets/{{ticketCitizenM9}}', 'tokenArea', 403);
ownedProbe('Agent denied lifecycle review on own ticket', '/api/tickets/{{ticketAgentM6}}/review', 'tokenAgent', 403, 'post');
ownedProbe('Area responsible denied lifecycle review', '/api/tickets/{{ticketAreaM6}}/review', 'tokenArea', 403, 'post');
ownedProbe('Area responsible denied lifecycle routing', '/api/tickets/{{ticketAreaM6}}/route', 'tokenArea', 403, 'post');

fs.mkdirSync(output, { recursive: true });
fs.writeFileSync(path.join(output, 'anonymous.postman_collection.json'), `${JSON.stringify(collection('DDA2 protected anonymous operations and public smoke', [...anonymous, ...smoke]), null, 2)}\n`);
fs.writeFileSync(path.join(output, 'role-bfla.postman_collection.json'), `${JSON.stringify(collection('DDA2 synthetic role boundary probes', roleRequests), null, 2)}\n`);
fs.writeFileSync(path.join(output, 'ownership-bfla.postman_collection.json'), `${JSON.stringify(collection('DDA2 synthetic ownership and lifecycle BFLA probes', ownershipRequests), null, 2)}\n`);
fs.writeFileSync(path.join(output, 'mass-assignment.postman_collection.json'), `${JSON.stringify(collection('DDA2 ticket-create mass-assignment probe', massAssignment), null, 2)}\n`);
process.stdout.write(JSON.stringify({ protectedOperations: protectedCount, anonymousRequests: anonymous.length, publicSmokeRequests: smoke.length, roleRequests: roleRequests.length, ownershipRequests: ownershipRequests.length, massAssignmentRequests: massAssignment.length }) + '\n');

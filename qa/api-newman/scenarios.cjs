const denials = require('./denials.cjs');

const neighborhoods = '/api/catalog/neighborhoods';
const neighborhood = neighborhoods + '/{neighborhoodId}';
const login = '/api/auth/login';
const identity = '/api/auth/me';
const labels = '/api/staff/labels';
const agents = '/api/admin/agents';
const admin = 'Bearer {{tokenAdmin}}';
const citizen = 'Bearer {{tokenCitizen}}';
const missingNeighborhood = neighborhoods + '/00000000-0000-4000-8000-000000000001';

function scenario(id, method, route, status, fixture, options = {}) {
  return { id, method, route, status, fixture, url: route, ...options };
}

function loginAs(account, role, variable) {
  const credentialPlaceholder = '{{password' + variable + '}}';
  return scenario('login-' + variable.toLowerCase(), 'POST', login, 200, 'Cuenta MOCK ' + role, {
    body: { username: account, password: credentialPlaceholder },
    checks: [
      'pm.test("expected identity role", () => pm.expect(pm.response.json().identity.role).to.eql("' + role + '"));',
      'if (pm.response.code === 200) pm.environment.set("token' + variable + '", pm.response.json().token);'
    ]
  });
}

function capture(variable, property) {
  return ['if (pm.response.code < 300) pm.environment.set(' + JSON.stringify(variable) + ', pm.response.json()[' + JSON.stringify(property) + ']);'];
}

const label = scenario('create-label', 'POST', labels, 201, 'Etiqueta QA nueva, rol ADMIN', {
  auth: admin, body: { code: 'DDA2_NEWMAN_OK', name: 'Etiqueta Newman QA' }, checks: capture('primaryLabelId', 'id')
});

const scenarios = [
  scenario('list-neighborhoods', 'GET', neighborhoods, 200, 'Barrios de Flyway', {
    checks: [
      'pm.test("seeded neighborhoods are available", () => pm.expect(pm.response.json()).to.be.a("array").that.is.not.empty);',
      'if (pm.response.code === 200) pm.environment.set("neighborhoodId", pm.response.json()[0].id);'
    ]
  }),
  scenario('read-neighborhood', 'GET', neighborhood, 200, 'ID devuelto por el listado', {
    url: neighborhoods + '/{{neighborhoodId}}'
  }),
  scenario('unknown-neighborhood', 'GET', neighborhood, 404, 'UUID sintético ausente', { url: missingNeighborhood }),
  scenario('neighborhoods-unacceptable', 'GET', neighborhoods, 406, 'Accept incompatible', { accept: 'application/problem+json' }),
  scenario('neighborhood-unacceptable', 'GET', neighborhood, 406, 'ID de Flyway, Accept incompatible', {
    url: neighborhoods + '/{{neighborhoodId}}', accept: 'application/problem+json'
  }),
  scenario('invalid-login', 'POST', login, 400, 'Cuerpo vacío', { body: {} }),
  scenario('identity-without-token', 'GET', identity, 401, 'Sin token'),
  loginAs('module.admin@example.test', 'ADMIN', 'Admin'),
  loginAs('citizen@example.test', 'CITIZEN', 'Citizen'),
  loginAs('agent@example.test', 'AGENT', 'Agent'),
  loginAs('area.responsible@example.test', 'AREA_RESPONSIBLE', 'Area'),
  scenario('identity-as-admin', 'GET', identity, 200, 'Token ADMIN', { auth: admin }),
  scenario('directory-as-citizen', 'GET', agents, 403, 'Token CITIZEN', { auth: citizen }),
  scenario('directory-as-admin', 'GET', agents, 200, 'Token ADMIN', { auth: admin }),
  scenario('create-label-as-citizen', 'POST', labels, 403, 'Token CITIZEN, etiqueta de control', {
    auth: citizen, body: { code: 'DDA2_NEWMAN_FORBIDDEN', name: 'Etiqueta rechazada' }
  }),
  label,
  scenario('duplicate-label', 'POST', labels, 409, 'Mismo código de la etiqueta QA', { auth: admin, body: label.body }),
  scenario('create-label-unacceptable', 'POST', labels, 406, 'Token ADMIN, Accept incompatible', {
    auth: admin, accept: 'application/problem+json', body: { code: 'DDA2_NEWMAN_REJECTED', name: 'Etiqueta rechazada' }
  }),
  scenario('list-labels', 'GET', labels, 200, 'Token ADMIN, etiqueta QA creada', {
    auth: admin,
    checks: ['pm.test("created label is present", () => pm.expect(pm.response.json().map(label => label.code)).to.include("DDA2_NEWMAN_OK"));']
  }),
  scenario('labels-without-token', 'GET', labels, 401, 'Sin token')
];

const context = { scenario, admin, citizen, area: 'Bearer {{tokenArea}}', agent: 'Bearer {{tokenAgent}}', capture };
scenarios.push(...require('./catalog.cjs')(context), ...require('./tickets.cjs')(context), ...require('./staff.cjs')(context),
  ...require('./lifecycle.cjs')(context), ...require('./advanced.cjs')(context), ...require('./session.cjs')(context));
module.exports = [...scenarios, ...denials(scenarios)];

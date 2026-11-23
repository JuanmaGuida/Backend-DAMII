module.exports = ({ scenario, admin, capture }) => {
  const root = '/api/catalog';
  const adminRoot = '/api/admin/catalog';
  const category = '/categories/{categoryId}';
  const subcategory = '/subcategories/{subcategoryId}';
  const type = '/request-types/{requestTypeId}';
  const typeInput = {
    subcategoryId: '{{catalogSubcategoryId}}', code: 'DDA2_NEWMAN_TYPE', name: 'Reclamo sintético QA',
    description: 'Tipo exclusivo de Newman', ticketType: 'COMPLAINT', responsibleAreaId: 'M2',
    minimumPriority: 'LOW', baseRisk: 'LOW', affectedPopulationFactor: 0,
    allowsAnonymous: true, requiresLocation: false
  };
  const ids = { categoryId: 'catalogCategoryId', subcategoryId: 'catalogSubcategoryId', requestTypeId: 'catalogTypeId' };
  const bindIds = route => route.replace(/\{(\w+)\}/g, (_, id) => '{{' + ids[id] + '}}');
  const staff = (id, method, route, status, options = {}) => scenario(id, method, adminRoot + route, status, 'Catálogo sintético QA, ADMIN', {
    url: adminRoot + bindIds(route), auth: admin, ...options
  });
  const publicGet = (id, route, status, url, accept) => scenario(id, 'GET', root + route, status, 'Catálogo sintético QA', {
    url: url ?? root + bindIds(route), accept
  });
  const lifecycle = (name, route) => [
    staff('deactivate-' + name, 'POST', route + '/deactivate', 200, {
      checks: ['pm.test("resource is inactive", () => pm.expect(pm.response.json().active).to.eql(false));']
    }),
    staff('activate-' + name, 'POST', route + '/activate', 200, {
      checks: ['pm.test("resource is active", () => pm.expect(pm.response.json().active).to.eql(true));']
    })
  ];
  return [
    publicGet('categories', '/categories', 200),
    publicGet('categories-unacceptable', '/categories', 406, undefined, 'application/problem+json'),
    staff('admin-categories', 'GET', '/categories', 200),
    staff('create-category', 'POST', '/categories', 201, {
      body: { name: 'Categoría Newman QA', description: 'Fixture de catálogo' }, checks: capture('catalogCategoryId', 'id')
    }),
    staff('update-category', 'PUT', category, 200, { body: { name: 'Categoría Newman QA actualizada', description: 'Fixture de catálogo' } }),
    ...lifecycle('category', category),
    staff('create-subcategory', 'POST', '/subcategories', 201, {
      body: { categoryId: '{{catalogCategoryId}}', name: 'Subcategoría Newman QA' },
      numericVariables: ['catalogCategoryId'], checks: capture('catalogSubcategoryId', 'id')
    }),
    staff('update-subcategory', 'PUT', subcategory, 200, {
      body: { categoryId: '{{catalogCategoryId}}', name: 'Subcategoría Newman QA actualizada' }, numericVariables: ['catalogCategoryId']
    }),
    ...lifecycle('subcategory', subcategory),
    staff('admin-subcategories', 'GET', category + '/subcategories', 200),
    publicGet('public-subcategories', category + '/subcategories', 200),
    publicGet('public-subcategories-bad-id', category + '/subcategories', 400, root + '/categories/invalid/subcategories'),
    staff('create-request-type', 'POST', '/request-types', 201, {
      body: typeInput, numericVariables: ['catalogSubcategoryId'], checks: capture('catalogTypeId', 'id')
    }),
    staff('create-alternative-type', 'POST', '/request-types', 201, {
      body: { ...typeInput, code: 'DDA2_NEWMAN_ALTERNATIVE', name: 'Reclasificación sintética QA' },
      numericVariables: ['catalogSubcategoryId'], checks: capture('alternativeTypeId', 'id')
    }),
    staff('update-request-type', 'PUT', type, 200, {
      body: { ...typeInput, name: 'Reclamo sintético QA actualizado' }, numericVariables: ['catalogSubcategoryId']
    }),
    ...lifecycle('request-type', type),
    staff('admin-request-types', 'GET', subcategory + '/request-types', 200),
    publicGet('public-request-types', subcategory + '/request-types', 200),
    publicGet('public-request-types-bad-id', subcategory + '/request-types', 400, root + '/subcategories/invalid/request-types'),
    staff('save-request-form', 'PUT', type + '/form', 200, {
      body: { fields: [{ code: 'qa_detail', label: 'Detalle adicional', type: 'TEXT', displayOrder: 1, required: false }] }
    }),
    staff('admin-request-form', 'GET', type + '/form', 200),
    publicGet('public-request-form', type + '/form', 200),
    publicGet('public-request-form-bad-id', type + '/form', 400, root + '/request-types/invalid/form')
  ];
};

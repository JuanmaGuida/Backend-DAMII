module.exports = ({ scenario, admin, area, capture }) => {
  const labels = '/api/staff/labels';
  const label = labels + '/{labelId}';
  const link = labels + '/{{primaryLabelId}}';
  const indicators = ['overview', 'export', 'categories', 'neighborhoods', 'priorities', 'areas', 'sla'];
  return [
    scenario('label-detail', 'GET', label, 200, 'Etiqueta existente, ADMIN', { url: link, auth: admin }),
    scenario('update-label', 'PUT', label, 200, 'Etiqueta existente, ADMIN', {
      url: link, auth: admin, body: { name: 'Etiqueta Newman QA actualizada', active: true }
    }),
    scenario('label-tickets', 'GET', label + '/tickets', 200, 'Etiqueta existente, ADMIN', { url: link + '/tickets', auth: admin }),
    scenario('create-disposable-label', 'POST', labels, 201, 'Etiqueta sin asociaciones, ADMIN', {
      auth: admin, body: { code: 'DDA2_NEWMAN_DELETE', name: 'Etiqueta descartable Newman' }, checks: capture('deleteLabelId', 'id')
    }),
    scenario('delete-disposable-label', 'DELETE', label, 204, 'Etiqueta sin asociaciones, ADMIN', {
      url: labels + '/{{deleteLabelId}}', auth: admin
    }),
    ...indicators.map(key => scenario('indicators-' + key, 'GET', '/api/indicators/' + key, 200, 'Indicadores de la base QA, ADMIN', {
      auth: admin,
      ...(key === 'export' ? {
        accept: 'text/csv, application/problem+json',
        checks: ['pm.test("CSV attachment is present", () => { pm.expect(pm.response.headers.get("Content-Disposition")).to.include("metricas.csv"); const rows = pm.response.text().trim().split(/\\r?\\n/); pm.expect(rows[0]).to.include("Creado"); pm.expect(rows.slice(1).some(row => row.includes(pm.environment.get("ownedPublicId")))).to.eql(true); });']
      } : {})
    })),
    scenario('area-tickets', 'GET', '/api/staff/tickets/area', 200, 'Responsable MOCK de M6', { auth: area }),
    scenario('own-tickets', 'GET', '/api/me/tickets', 200, 'Ciudadano MOCK', { auth: 'Bearer {{tokenCitizen}}' }),
    scenario('ticket-inbox', 'GET', '/api/tickets', 200, 'Bandeja operativa, AGENT', { auth: 'Bearer {{tokenAgent}}' })
  ];
};

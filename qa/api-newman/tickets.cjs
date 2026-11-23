module.exports = ({ scenario, admin, citizen, agent, capture }) => {
  const tickets = '/api/tickets';
  const mine = '/api/me/tickets/{ticketId}/tracking-code';
  const ticket = tickets + '/{ticketId}';
  const ownUrl = tickets + '/{{ownedTicketId}}';
  const tracking = '/api/tracking';
  const anon = tracking + '/actions';
  const staff = '/api/staff/tickets/{ticketId}';
  const staffUrl = '/api/staff/tickets/{{ownedTicketId}}';
  const normalBody = { requestTypeId: '{{catalogTypeId}}', summary: 'Reclamo identificado QA Newman',
    description: 'Detalle sintético del reclamo identificado', formData: {} };
  const numericVariables = ['catalogTypeId'];
  const idempotency = [{ key: 'Idempotency-Key', value: 'newman-owned-ticket' }];
  const credentials = { trackingCode: '{{anonymousTrackingCode}}', anonymousAccessPassword: '{{anonymousAccess}}' };
  const png = 'src/test/resources/attachments/valid.png';
  const message = { visibility: 'PUBLIC', text: 'Mensaje sintético de QA' };
  const owned = (id, method, suffix, status, options = {}) => scenario(id, method, ticket + suffix, status, 'Reclamo identificado, ciudadano propietario', {
    url: ownUrl + suffix, auth: citizen, ...options
  });
  const staffTicket = (id, method, suffix, status, options = {}) => scenario(id, method, staff + suffix, status, 'Reclamo M6, capacidad staff', {
    url: staffUrl + suffix, auth: agent, ...options
  });
  const trackingPost = (id, route, status, options = {}) => scenario(id, 'POST', route, status, 'Reclamo anónimo con credenciales sintéticas', options);
  function upload(data) {
    return [
      { key: 'data', type: 'text', value: JSON.stringify(data), contentType: 'application/json' },
      { key: 'attachments', type: 'file', src: png, contentType: 'image/png' }
    ];
  }
  function captureFirst(variable) {
    return ['if (pm.response.code === 201) pm.environment.set(' + JSON.stringify(variable) + ', pm.response.json()[0].id);'];
  }
  return [
    scenario('create-owned-ticket', 'POST', tickets, 201, 'Tipo de trámite exclusivo QA, ciudadano MOCK', {
      auth: citizen, body: normalBody, headers: idempotency, numericVariables, checks: [...capture('ownedTicketId', 'ticketId'), ...capture('ownedPublicId', 'publicId')]
    }),
    scenario('retry-owned-ticket', 'POST', tickets, 201, 'Mismo trámite, cuerpo y clave de idempotencia', {
      auth: citizen, body: normalBody, headers: idempotency, numericVariables,
      checks: ['pm.test("retry returns the same resource", () => pm.expect(pm.response.json().ticketId).to.eql(pm.environment.get("ownedTicketId")));']
    }),
    scenario('conflicting-ticket-key', 'POST', tickets, 409, 'Misma clave y resumen distinto', {
      auth: citizen, body: { ...normalBody, summary: 'Resumen modificado para verificar conflicto' }, headers: idempotency, numericVariables,
      checks: ['pm.test("idempotency conflict", () => pm.expect(pm.response.json().code).to.eql("IDEMPOTENCY_KEY_CONFLICT"));']
    }),
    scenario('create-anonymous-ticket', 'POST', tickets, 201, 'Tipo QA habilitado para anónimos', {
      body: { ...normalBody, summary: 'Reclamo anónimo QA Newman' }, numericVariables,
      checks: [...capture('anonymousTicketId', 'ticketId'), ...capture('anonymousTrackingCode', 'trackingCode'),
        ...capture('anonymousAccess', 'generatedAnonymousAccessPassword')]
    }),
    owned('owned-ticket-detail', 'GET', '', 200),
    scenario('get-own-tracking-code', 'GET', mine, 200, 'Reclamo identificado, código cifrado recuperable', {
      auth: citizen, url: '/api/me/tickets/{{ownedTicketId}}/tracking-code'
    }),
    owned('external-attachment-references', 'GET', '/external-attachments', 200),
    staffTicket('staff-ticket-detail', 'GET', '', 200),
    staffTicket('staff-citizen-view', 'GET', '/citizen-view', 200),
    staffTicket('duplicate-candidates', 'GET', '/duplicate-candidates', 200),
    staffTicket('ticket-insights-fallback', 'POST', '/ai-insights', 200),
    scenario('classify-fallback', 'POST', '/api/ai/classify', 200, 'Entrada sintética con IA externa desactivada', {
      body: { description: 'Detalle sintético de luminaria durante la prueba local' }
    }),
    scenario('classify-invalid', 'POST', '/api/ai/classify', 400, 'Falta la descripción', { body: {} }),
    staffTicket('assign-primary-label', 'POST', '/labels', 204, { auth: admin, body: { labelIds: ['{{primaryLabelId}}'] } }),
    staffTicket('remove-primary-label', 'DELETE', '/labels/{labelId}', 204, { auth: admin, url: staffUrl + '/labels/{{primaryLabelId}}' }),
    owned('add-owned-message', 'POST', '/messages', 201, { body: message, checks: capture('messageId', 'id') }),
    owned('list-owned-messages', 'GET', '/messages', 200),
    owned('read-owned-message', 'GET', '/messages/{messageId}', 200, { url: ownUrl + '/messages/{{messageId}}' }),
    owned('edit-owned-message', 'PATCH', '/messages/{messageId}', 200, {
      url: ownUrl + '/messages/{{messageId}}', body: { text: 'Mensaje sintético actualizado' }
    }),
    owned('delete-owned-message', 'DELETE', '/messages/{messageId}', 204, { url: ownUrl + '/messages/{{messageId}}' }),
    owned('upload-owned-attachment', 'POST', '/attachments', 201, {
      formdata: upload({ visibility: 'PUBLIC' }), checks: captureFirst('ownedAttachmentId')
    }),
    scenario('download-owned-attachment', 'GET', '/api/attachments/{attachmentId}/content', 200,
      'PNG existente del propietario', {
        url: '/api/attachments/{{ownedAttachmentId}}/content', auth: citizen, accept: 'image/png, application/problem+json',
        binaryFixture: png
      }),
    trackingPost('access-anonymous-ticket', tracking + '/access', 200, { body: credentials }),
    trackingPost('tracking-empty', tracking + '/access', 400, { body: {} }),
    trackingPost('anonymous-message', anon + '/messages', 201, { body: { ...credentials, payload: message } }),
    trackingPost('anonymous-message-invalid', anon + '/messages', 400, { body: {} }),
    trackingPost('upload-anonymous-attachment', anon + '/attachments', 201, {
      formdata: upload({ ...credentials, payload: { visibility: 'PUBLIC' } }), checks: captureFirst('anonymousAttachmentId')
    }),
    trackingPost('upload-anonymous-no-file', anon + '/attachments', 400, {
      formdata: [{ key: 'data', type: 'text', value: '{}', contentType: 'application/json' }]
    }),
    trackingPost('download-anonymous-attachment', anon + '/attachments/{attachmentId}/content', 200, {
      url: anon + '/attachments/{{anonymousAttachmentId}}/content', accept: 'image/png, application/problem+json',
      body: credentials, binaryFixture: png
    }),
    trackingPost('download-anonymous-invalid', anon + '/attachments/{attachmentId}/content', 400, {
      url: anon + '/attachments/{{anonymousAttachmentId}}/content', body: {}
    })
  ];
};

module.exports = ({ scenario, citizen, agent, capture }) => {
  const root = '/api/tickets/{ticketId}';
  const staff = '/api/staff/tickets/{ticketId}';
  const publicRoot = '/api/tracking/actions';
  const body = { requestTypeId: '{{catalogTypeId}}', summary: 'Fixture Newman para cancelar',
    description: 'Reclamo separado del flujo de revisión y resolución', formData: {} };
  const withdrawal = { reasonCode: 'WITHDRAWN_BY_CITIZEN' };
  const credentials = { trackingCode: '{{anonymousTrackingCode}}', anonymousAccessPassword: '{{anonymousAccess}}' };
  const information = { messageForCitizen: 'Detalle adicional para verificar el reclamo.' };
  const answer = { responseMessage: 'Respuesta de la persona propietaria.' };
  const resolution = { type: 'ACTION_COMPLETED', publicMessage: 'Gestión de prueba completada.' };
  const reopen = { reason: 'Verificar el ciclo de reapertura con datos sintéticos.' };
  const numericVariables = ['catalogTypeId'];

  const ticket = (id, method, suffix, status, { reference = 'ownedTicketId', auth = agent, ...options } = {}) =>
    scenario(id, method, root + suffix, status, 'Ticket QA preparado por el flujo anterior', {
      url: '/api/tickets/{{' + reference + '}}' + suffix, auth, ...options
    });
  const anon = (id, suffix, status, data) => scenario(id, 'POST', publicRoot + suffix, status,
    'Credenciales sintéticas y estado preparado por el flujo anterior', { body: data });
  const invalid = (id, suffix) => anon(id + '-invalid', suffix, 400, {});

  return [
    ticket('own-cancellation-history', 'GET', '/cancellation-requests', 200, { auth: citizen }),
    ...['cancellation-requests', 'cancellation-conflicts', 'reclassification-proposals'].map(section =>
      scenario('staff-' + section, 'GET', staff + '/' + section, 200, 'Historial del ticket sintético QA', {
        url: '/api/staff/tickets/{{ownedTicketId}}/' + section, auth: agent
      })),
    anon('anonymous-cancellation-history', '/cancellation-requests/history', 200, credentials),
    invalid('anonymous-cancellation-history', '/cancellation-requests/history'),
    ticket('review-owned', 'POST', '/review', 200),
    ticket('classification', 'GET', '/classification', 200, {
      checks: ['pm.test("classification exposes ETag", () => pm.expect(pm.response.headers.get("ETag")).to.be.ok);',
        'if (pm.response.code === 200) pm.environment.set("classificationTag", pm.response.headers.get("ETag"));']
    }),
    ticket('correct-classification', 'PATCH', '/classification', 200, {
      body: { requestTypeId: '{{catalogTypeId}}' }, numericVariables,
      headers: [{ key: 'If-Match', value: '{{classificationTag}}' }]
    }),
    ticket('request-owned-information', 'POST', '/information-request', 201, { body: information }),
    ticket('answer-owned-information', 'POST', '/information-response', 200, { auth: citizen, body: answer }),
    ticket('route-owned', 'POST', '/route', 200),
    ticket('resolve-owned', 'POST', '/resolution', 201, { body: resolution }),
    ticket('reopen-owned', 'POST', '/resolution/reopen', 200, { auth: citizen, body: reopen }),
    ticket('resolve-owned-again', 'POST', '/resolution', 201, { body: resolution }),
    ticket('confirm-owned', 'POST', '/resolution/confirm', 200, { auth: citizen }),
    ticket('survey-owned', 'POST', '/satisfaction-survey', 201, {
      auth: citizen, body: { score: 5, comment: 'Evaluación sintética' }
    }),
    ticket('review-anonymous', 'POST', '/review', 200, { reference: 'anonymousTicketId' }),
    ticket('request-anonymous-information', 'POST', '/information-request', 201, { reference: 'anonymousTicketId', body: information }),
    anon('answer-anonymous-information', '/information-response', 200, { ...credentials, payload: answer }),
    invalid('answer-anonymous-information', '/information-response'),
    ticket('route-anonymous', 'POST', '/route', 200, { reference: 'anonymousTicketId' }),
    ticket('resolve-anonymous', 'POST', '/resolution', 201, { reference: 'anonymousTicketId', body: resolution }),
    anon('reopen-anonymous', '/reopen', 200, { ...credentials, payload: reopen }),
    invalid('reopen-anonymous', '/reopen'),
    ticket('resolve-anonymous-again', 'POST', '/resolution', 201, { reference: 'anonymousTicketId', body: resolution }),
    anon('confirm-anonymous', '/confirm-resolution', 200, credentials),
    invalid('confirm-anonymous', '/confirm-resolution'),
    scenario('create-cancellable-owned', 'POST', '/api/tickets', 201, 'Ticket identificado para cancelar', {
      auth: citizen, body, numericVariables, checks: capture('cancellableOwnedId', 'ticketId')
    }),
    ticket('cancel-owned', 'POST', '/cancel', 200, { reference: 'cancellableOwnedId', auth: citizen, body: withdrawal }),
    scenario('create-cancellable-anonymous', 'POST', '/api/tickets', 201, 'Ticket anónimo para cancelar', {
      body: { ...body, summary: 'Fixture anónimo Newman para cancelar' }, numericVariables,
      checks: [...capture('cancellableTrackingCode', 'trackingCode'), ...capture('cancellableAccess', 'generatedAnonymousAccessPassword')]
    }),
    anon('cancel-anonymous', '/cancel', 200, { trackingCode: '{{cancellableTrackingCode}}',
      anonymousAccessPassword: '{{cancellableAccess}}', payload: withdrawal }),
    invalid('cancel-anonymous', '/cancel')
  ];
};

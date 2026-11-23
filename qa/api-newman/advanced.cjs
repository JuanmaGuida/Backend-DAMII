module.exports = ({ scenario, agent, citizen, capture }) => {
  const tickets = '/api/tickets';
  const ticket = tickets + '/{ticketId}';
  const staff = '/api/staff/tickets/{ticketId}';
  const anonymousRequests = '/api/tracking/actions/cancellation-requests';
  const numericVariables = ['catalogTypeId'];
  const reason = { reasonCode: 'WITHDRAWN_BY_CITIZEN', reason: 'Solicitud sintética para comprobar revisión.' };
  const proposal = '/reclassification-proposals';
  const requests = '/cancellation-requests';
  const request = requests + '/{requestId}';
  const loginFixture = 'Familia de reclamos sintéticos QA';
  const bindTicket = (root, variable) => root.replace('{ticketId}', '{{' + variable + '}}');

  function fixture(id, state, authenticated = true) {
    const fields = {
      body: { requestTypeId: '{{catalogTypeId}}', summary: 'Newman QA ' + id, description: 'Fixture de ' + state, formData: {} },
      numericVariables, auth: authenticated ? citizen : undefined,
      checks: capture(id + 'Id', 'ticketId')
    };
    if (!authenticated) fields.checks.push(...capture(id + 'Tracking', 'trackingCode'), ...capture(id + 'Credential', 'generatedAnonymousAccessPassword'));
    return scenario('fixture-' + id, 'POST', tickets, 201, state, fields);
  }

  function caseOn(root, reference, id, method, suffix, status, options = {}) {
    return scenario(id, method, root + suffix, status, loginFixture, {
      url: bindTicket(root, reference + 'Id') + suffix, auth: agent, ...options
    });
  }

  const onProposal = (id, method, suffix, status, options = {}) => caseOn(staff, 'reclassification', id, method, proposal + suffix, status, options);
  const link = (name, child, parent) => caseOn(staff, child, name, 'POST', '/duplicate', 200, {
    body: { mainTicketId: '{{' + parent + 'Id}}' }
  });
  const target = { requestTypeId: '{{alternativeTypeId}}' };
  const oneRequestUrl = bindTicket(staff, 'identifiedMainId') + requests + '/{{cancelRequestId}}';
  const oneProposalUrl = bindTicket(staff, 'reclassificationId') + proposal + '/{{proposalId}}';

  const conflicts = '/cancellation-conflicts';
  const conflictTicket = 'ebc00000-0000-4000-8000-000000000001';
  const conflictUrl = staff.replace('{ticketId}', conflictTicket) + conflicts;
  return [
    scenario('read-pending-conflict', 'GET', staff + conflicts, 200, 'Conflicto INFO_TIMEOUT sintético con duplicado', {
      url: conflictUrl, auth: agent,
      checks: [
        'pm.test("pending conflict exists", () => pm.expect(pm.response.json()[0].status).to.eql("PENDING"));',
        'if (pm.response.code === 200) pm.environment.set("cancellationConflictId", pm.response.json()[0].id);'
      ]
    }),
    scenario('resolve-cancellation-conflict', 'POST', staff + conflicts + '/{conflictId}/resolve', 200, 'Conflicto INFO_TIMEOUT sintético, decisión motivada', {
      url: conflictUrl + '/{{cancellationConflictId}}/resolve', auth: agent,
      body: { decision: 'REJECTED', decisionReason: 'Se mantiene el reclamo de prueba.' },
      checks: ['pm.test("conflict was rejected", () => pm.expect(pm.response.json().status).to.eql("REJECTED"));']
    }),
    fixture('reclassification', 'Reclasificación inicial'),
    caseOn(ticket, 'reclassification', 'review-reclassification', 'POST', '/review', 200),
    onProposal('propose-reclassification', 'POST', '', 201, {
      body: target, numericVariables: ['alternativeTypeId'], checks: capture('proposalId', 'id')
    }),
    onProposal('reclassification-detail', 'GET', '/{proposalId}', 200, { url: oneProposalUrl }),
    onProposal('abandon-reclassification', 'POST', '/{proposalId}/abandon', 200, {
      url: oneProposalUrl + '/abandon', body: { reason: 'Fixture de primera propuesta descartada.' }
    }),
    onProposal('propose-reclassification-again', 'POST', '', 201, {
      body: target, numericVariables: ['alternativeTypeId'], checks: capture('proposalId', 'id')
    }),
    onProposal('confirm-reclassification', 'POST', '/{proposalId}/confirm', 200, { url: oneProposalUrl + '/confirm' }),
    fixture('identifiedMain', 'Principal identificado'),
    fixture('identifiedChild', 'Duplicado identificado'),
    link('link-identified-duplicate', 'identifiedChild', 'identifiedMain'),
    caseOn(ticket, 'identifiedMain', 'request-cancellation', 'POST', requests, 201, {
      auth: citizen, body: reason, checks: capture('cancelRequestId', 'id')
    }),
    caseOn(staff, 'identifiedMain', 'get-cancellation-request', 'GET', request, 200, { url: oneRequestUrl }),
    caseOn(staff, 'identifiedMain', 'reject-cancellation-request', 'POST', request + '/reject', 200, {
      url: oneRequestUrl + '/reject', body: { decisionReason: 'Justificación sintética de rechazo.' }
    }),
    caseOn(ticket, 'identifiedMain', 'request-cancellation-again', 'POST', requests, 201, {
      auth: citizen, body: reason, checks: capture('cancelRequestId', 'id')
    }),
    caseOn(staff, 'identifiedMain', 'approve-cancellation-request', 'POST', request + '/approve', 200, {
      url: oneRequestUrl + '/approve', body: { cancellationScope: 'GLOBAL', decisionReason: 'Cancelación global sintética.' }
    }),
    fixture('anonymousMain', 'Principal anónimo', false),
    fixture('anonymousChild', 'Duplicado del principal anónimo'),
    link('link-anonymous-duplicate', 'anonymousChild', 'anonymousMain'),
    scenario('request-anonymous-cancellation', 'POST', anonymousRequests, 201, 'Principal anónimo con duplicado activo', {
      body: { trackingCode: '{{anonymousMainTracking}}', anonymousAccessPassword: '{{anonymousMainCredential}}', payload: reason }
    }),
    scenario('request-anonymous-cancellation-invalid', 'POST', anonymousRequests, 400, 'Cuerpo sin campos requeridos', { body: {} })
  ];
};

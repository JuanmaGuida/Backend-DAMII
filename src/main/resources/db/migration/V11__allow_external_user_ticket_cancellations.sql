ALTER TABLE ticket_cancellations DROP CONSTRAINT ck_ticket_cancellation_actor;

ALTER TABLE ticket_cancellations ADD CONSTRAINT ck_ticket_cancellation_actor CHECK (
    cancelled_by_type IN ('CITIZEN', 'AGENT', 'AREA_RESPONSIBLE', 'ADMIN', 'EXTERNAL_USER', 'SYSTEM')
);

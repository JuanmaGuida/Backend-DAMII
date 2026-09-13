ALTER TABLE information_requests
    DROP CONSTRAINT ck_information_request_status;

ALTER TABLE information_requests
    ADD CONSTRAINT ck_information_request_status
        CHECK (status IN ('PENDING', 'ANSWERED', 'EXPIRED', 'CANCELLED'));

CREATE TABLE satisfaction_surveys (
                                      id BIGSERIAL PRIMARY KEY,
                                      ticket_id UUID NOT NULL,
                                      score SMALLINT NOT NULL,
                                      comment TEXT,
                                      created_at TIMESTAMP WITH TIME ZONE NOT NULL,

                                      CONSTRAINT fk_satisfaction_survey_ticket
                                          FOREIGN KEY (ticket_id) REFERENCES tickets(id),
                                      CONSTRAINT uk_satisfaction_survey_ticket UNIQUE (ticket_id),
                                      CONSTRAINT ck_satisfaction_survey_score CHECK (score BETWEEN 1 AND 5)
);
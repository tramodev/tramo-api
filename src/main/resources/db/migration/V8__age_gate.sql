CREATE TABLE age_rejection_attempts (
    id BIGSERIAL PRIMARY KEY,
    ip_address VARCHAR(64) NOT NULL,
    rejected_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_age_rejection_attempts_ip_rejected_at
    ON age_rejection_attempts (ip_address, rejected_at);

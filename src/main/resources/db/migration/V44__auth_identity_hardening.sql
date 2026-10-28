CREATE UNIQUE INDEX idx_users_email_ignore_case ON users (lower(email));
ALTER TABLE email_verification_token RENAME COLUMN token TO token_hash;
UPDATE email_verification_token SET token_hash = encode(sha256(convert_to(token_hash, 'UTF8')), 'hex');

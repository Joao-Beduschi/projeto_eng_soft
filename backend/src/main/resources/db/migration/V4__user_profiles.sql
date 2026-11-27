ALTER TABLE users ADD COLUMN display_name VARCHAR(100);

CREATE TABLE email_change_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    new_email VARCHAR(320) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    credential_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CHECK (expires_at > created_at)
);

CREATE INDEX idx_email_change_tokens_user_created ON email_change_tokens (user_id, created_at);
CREATE INDEX idx_email_change_tokens_expires ON email_change_tokens (expires_at);

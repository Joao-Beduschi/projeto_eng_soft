ALTER TABLE users ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CHECK (expires_at > created_at)
);

CREATE INDEX idx_reset_tokens_user_created ON password_reset_tokens (user_id, created_at);
CREATE INDEX idx_reset_tokens_expires ON password_reset_tokens (expires_at);

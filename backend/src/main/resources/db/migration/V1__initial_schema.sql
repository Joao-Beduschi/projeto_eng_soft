CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(320) NOT NULL,
    password_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX users_email_unique ON users (LOWER(email));

CREATE TABLE folders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id),
    parent_id UUID,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (id, owner_id),
    FOREIGN KEY (parent_id, owner_id) REFERENCES folders(id, owner_id),
    CHECK (LENGTH(TRIM(name)) > 0),
    CHECK (parent_id IS DISTINCT FROM id)
);

CREATE TABLE files (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id),
    folder_id UUID,
    name VARCHAR(255) NOT NULL,
    storage_key UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    size_bytes BIGINT NOT NULL,
    mime_type VARCHAR(255) NOT NULL DEFAULT 'application/octet-stream',
    sha256 CHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    FOREIGN KEY (folder_id, owner_id) REFERENCES folders(id, owner_id),
    CHECK (LENGTH(TRIM(name)) > 0),
    CHECK (size_bytes >= 0),
    CHECK (status IN ('pending', 'ready', 'deleting')),
    CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_files_owner_folder ON files(owner_id, folder_id);
CREATE INDEX idx_folders_owner_parent ON folders(owner_id, parent_id);
CREATE INDEX idx_files_owner_name ON files(owner_id, name);
CREATE INDEX idx_files_lifecycle ON files(status, created_at) WHERE status <> 'ready';

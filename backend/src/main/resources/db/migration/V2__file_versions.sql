-- Version history is additive. Existing object keys and file bytes are not moved or rewritten.
CREATE TABLE file_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_id UUID NOT NULL REFERENCES files(id) ON DELETE RESTRICT,
    version_number INTEGER NOT NULL CHECK (version_number > 0),
    uploader_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    storage_key UUID NOT NULL UNIQUE,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256 CHAR(64),
    change_message VARCHAR(1000),
    status VARCHAR(16) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'ready', 'deleting')),
    UNIQUE (file_id, version_number),
    UNIQUE (id, file_id),
    CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_file_versions_history
    ON file_versions(file_id, version_number);
CREATE INDEX idx_file_versions_lifecycle
    ON file_versions(status, created_at) WHERE status <> 'ready';

ALTER TABLE files ADD COLUMN current_version_id UUID;

-- Backfill every pre-existing file as version 1. This is idempotent, and reuses the
-- existing storage key so the old bytes remain in place and immediately downloadable.
INSERT INTO file_versions (
    file_id, version_number, uploader_user_id, created_at,
    storage_key, size_bytes, sha256, change_message, status
)
SELECT f.id, 1, f.owner_id, f.created_at,
       f.storage_key, f.size_bytes, f.sha256,
       'Backfilled from the pre-version file record', f.status
FROM files f
WHERE NOT EXISTS (
    SELECT 1 FROM file_versions v
    WHERE v.file_id = f.id AND v.version_number = 1
);

UPDATE files f
SET current_version_id = v.id
FROM file_versions v
WHERE v.file_id = f.id
  AND v.version_number = 1
  AND f.status = 'ready';

ALTER TABLE files
    ADD CONSTRAINT fk_files_current_version
        FOREIGN KEY (current_version_id, id)
        REFERENCES file_versions(id, file_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT chk_files_ready_has_current_version
        CHECK (status <> 'ready' OR current_version_id IS NOT NULL);

-- Ready history is append-only. Rows may be removed only as part of the existing
-- whole-file deletion lifecycle after the parent file has entered `deleting`.
CREATE FUNCTION protect_ready_file_version_history() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.status = 'ready' THEN
        RAISE EXCEPTION 'ready file versions are immutable';
    END IF;
    IF TG_OP = 'DELETE' AND OLD.status = 'ready'
       AND EXISTS (
           SELECT 1 FROM files f
           WHERE f.id = OLD.file_id AND f.status <> 'deleting'
       ) THEN
        RAISE EXCEPTION 'ready file versions can only be removed with their logical file';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_protect_ready_file_version_history
    BEFORE UPDATE OR DELETE ON file_versions
    FOR EACH ROW EXECUTE FUNCTION protect_ready_file_version_history();

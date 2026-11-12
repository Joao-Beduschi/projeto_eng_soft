package com.cloudstorage.repository;

import com.cloudstorage.model.FileRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FileRepository {
    private static final String SELECT_COLUMNS = """
            id, owner_id, folder_id, name, storage_key, size_bytes, mime_type,
            sha256, status, created_at, updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public FileRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void createPending(FileRecord file) {
        jdbc.update("""
                INSERT INTO files
                    (id, owner_id, folder_id, name, storage_key, size_bytes, mime_type, status)
                VALUES
                    (:id, :ownerId, CAST(:folderId AS UUID), :name, :storageKey,
                     :sizeBytes, :mimeType, 'pending')
                """, parameters(file));
    }

    public boolean markReady(UUID id, UUID ownerId, String sha256, String mimeType) {
        return jdbc.update("""
                UPDATE files
                SET status = 'ready', sha256 = :sha256, mime_type = :mimeType,
                    updated_at = NOW()
                WHERE id = :id AND owner_id = :ownerId AND status = 'pending'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("ownerId", ownerId)
                .addValue("sha256", sha256)
                .addValue("mimeType", mimeType)) == 1;
    }

    public Optional<FileRecord> findReadyOwned(UUID id, UUID ownerId) {
        List<FileRecord> rows = jdbc.query("""
                SELECT %s FROM files
                WHERE id = :id AND owner_id = :ownerId AND status = 'ready'
                """.formatted(SELECT_COLUMNS), new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("ownerId", ownerId), this::mapFile);
        return rows.stream().findFirst();
    }

    public List<FileRecord> listReady(UUID ownerId, UUID folderId) {
        return jdbc.query("""
                SELECT %s FROM files
                WHERE owner_id = :ownerId
                  AND folder_id IS NOT DISTINCT FROM CAST(:folderId AS UUID)
                  AND status = 'ready'
                ORDER BY LOWER(name), created_at, id
                """.formatted(SELECT_COLUMNS), new MapSqlParameterSource()
                .addValue("ownerId", ownerId)
                .addValue("folderId", folderId), this::mapFile);
    }

    public boolean renameReady(UUID id, UUID ownerId, String name) {
        return jdbc.update("""
                UPDATE files SET name = :name, updated_at = NOW()
                WHERE id = :id AND owner_id = :ownerId AND status = 'ready'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("ownerId", ownerId)
                .addValue("name", name)) == 1;
    }

    public boolean moveReady(UUID id, UUID ownerId, UUID folderId) {
        return jdbc.update("""
                UPDATE files
                SET folder_id = CAST(:folderId AS UUID), updated_at = NOW()
                WHERE id = :id AND owner_id = :ownerId AND status = 'ready'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("ownerId", ownerId)
                .addValue("folderId", folderId)) == 1;
    }

    public Optional<FileRecord> markDeleting(UUID id, UUID ownerId) {
        int updated = jdbc.update("""
                UPDATE files SET status = 'deleting', updated_at = NOW()
                WHERE id = :id AND owner_id = :ownerId AND status = 'ready'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("ownerId", ownerId));
        if (updated != 1) {
            return Optional.empty();
        }
        List<FileRecord> rows = jdbc.query("""
                SELECT %s FROM files WHERE id = :id AND owner_id = :ownerId
                """.formatted(SELECT_COLUMNS), new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("ownerId", ownerId), this::mapFile);
        return rows.stream().findFirst();
    }

    public boolean deleteWithStatus(UUID id, String status) {
        return jdbc.update("DELETE FROM files WHERE id = :id AND status = :status",
                new MapSqlParameterSource().addValue("id", id).addValue("status", status)) == 1;
    }

    public List<FileRecord> findNonReady() {
        return jdbc.query("""
                SELECT %s FROM files
                WHERE status <> 'ready'
                ORDER BY created_at
                """.formatted(SELECT_COLUMNS), new MapSqlParameterSource(), this::mapFile);
    }

    public Set<UUID> findAllStorageKeys() {
        return jdbc.query("SELECT storage_key FROM files", new MapSqlParameterSource(),
                        (rs, row) -> rs.getObject(1, UUID.class))
                .stream().collect(Collectors.toUnmodifiableSet());
    }

    private MapSqlParameterSource parameters(FileRecord file) {
        return new MapSqlParameterSource()
                .addValue("id", file.id())
                .addValue("ownerId", file.ownerId())
                .addValue("folderId", file.folderId())
                .addValue("name", file.name())
                .addValue("storageKey", file.storageKey())
                .addValue("sizeBytes", file.sizeBytes())
                .addValue("mimeType", file.mimeType());
    }

    private FileRecord mapFile(ResultSet rs, int row) throws SQLException {
        return new FileRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                rs.getObject("folder_id", UUID.class),
                rs.getString("name"),
                rs.getObject("storage_key", UUID.class),
                rs.getLong("size_bytes"),
                rs.getString("mime_type"),
                rs.getString("sha256"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}

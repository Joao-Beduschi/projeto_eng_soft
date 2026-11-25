package com.cloudstorage.repository;

import com.cloudstorage.model.FileVersionRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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
public class FileVersionRepository {
    private static final String SELECT_COLUMNS = """
            id, file_id, version_number, uploader_user_id, created_at, storage_key,
            size_bytes, sha256, change_message, status
            """;
    private static final String SELECT_VERSION_COLUMNS = """
            v.id, v.file_id, v.version_number, v.uploader_user_id, v.created_at,
            v.storage_key, v.size_bytes, v.sha256, v.change_message, v.status
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public FileVersionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public FileVersionRecord createPending(FileVersionRecord version) {
        jdbc.update("""
                INSERT INTO file_versions
                    (id, file_id, version_number, uploader_user_id, storage_key,
                     size_bytes, change_message, status)
                VALUES
                    (:id, :fileId, :versionNumber, :uploaderUserId, :storageKey,
                     :sizeBytes, :changeMessage, 'pending')
                """, parameters(version));
        return jdbc.query("SELECT %s FROM file_versions WHERE id = :id".formatted(SELECT_COLUMNS),
                        new MapSqlParameterSource().addValue("id", version.id()), this::mapVersion)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Inserted file revision could not be read"));
    }

    public boolean markReady(UUID id, UUID fileId, long sizeBytes, String sha256) {
        return jdbc.update("""
                UPDATE file_versions
                SET status = 'ready', size_bytes = :sizeBytes, sha256 = :sha256
                WHERE id = :id AND file_id = :fileId AND status = 'pending'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("fileId", fileId)
                .addValue("sizeBytes", sizeBytes)
                .addValue("sha256", sha256)) == 1;
    }

    public boolean deletePending(UUID id) {
        return jdbc.update("DELETE FROM file_versions WHERE id = :id AND status = 'pending'",
                new MapSqlParameterSource().addValue("id", id)) == 1;
    }

    public int deleteAllForFile(UUID fileId) {
        return jdbc.update("DELETE FROM file_versions WHERE file_id = :fileId",
                new MapSqlParameterSource().addValue("fileId", fileId));
    }

    public Optional<FileVersionRecord> findCurrentReadyOwned(UUID fileId, UUID ownerId) {
        List<FileVersionRecord> rows = jdbc.query("""
                SELECT %s FROM files f
                JOIN file_versions v ON v.id = f.current_version_id AND v.file_id = f.id
                WHERE f.id = :fileId AND f.owner_id = :ownerId
                  AND f.status = 'ready' AND v.status = 'ready'
                """.formatted(SELECT_VERSION_COLUMNS), new MapSqlParameterSource()
                .addValue("fileId", fileId)
                .addValue("ownerId", ownerId), this::mapVersion);
        return rows.stream().findFirst();
    }

    public List<FileVersionRecord> listReadyOwned(UUID fileId, UUID ownerId) {
        return jdbc.query("""
                SELECT %s FROM files f
                JOIN file_versions v ON v.file_id = f.id
                WHERE f.id = :fileId AND f.owner_id = :ownerId
                  AND f.status = 'ready' AND v.status = 'ready'
                ORDER BY v.version_number ASC
                """.formatted(SELECT_VERSION_COLUMNS), new MapSqlParameterSource()
                .addValue("fileId", fileId)
                .addValue("ownerId", ownerId), this::mapVersion);
    }

    public Optional<FileVersionRecord> findReadyOwned(UUID fileId, int versionNumber, UUID ownerId) {
        List<FileVersionRecord> rows = jdbc.query("""
                SELECT %s FROM files f
                JOIN file_versions v ON v.file_id = f.id
                WHERE f.id = :fileId AND f.owner_id = :ownerId
                  AND f.status = 'ready' AND v.version_number = :versionNumber
                  AND v.status = 'ready'
                """.formatted(SELECT_VERSION_COLUMNS), new MapSqlParameterSource()
                .addValue("fileId", fileId)
                .addValue("versionNumber", versionNumber)
                .addValue("ownerId", ownerId), this::mapVersion);
        return rows.stream().findFirst();
    }

    public List<FileVersionRecord> findPendingForFile(UUID fileId) {
        return jdbc.query("""
                SELECT %s FROM file_versions
                WHERE file_id = :fileId AND status = 'pending'
                ORDER BY created_at
                """.formatted(SELECT_COLUMNS), new MapSqlParameterSource().addValue("fileId", fileId),
                this::mapVersion);
    }

    public List<FileVersionRecord> findStalePending(OffsetDateTime cutoff) {
        return jdbc.query("""
                SELECT %s FROM file_versions
                WHERE status = 'pending' AND created_at < :cutoff
                ORDER BY created_at
                """.formatted(SELECT_COLUMNS), new MapSqlParameterSource().addValue("cutoff", cutoff),
                this::mapVersion);
    }

    public Set<UUID> findStorageKeysForFile(UUID fileId) {
        return jdbc.query("SELECT storage_key FROM file_versions WHERE file_id = :fileId",
                        new MapSqlParameterSource().addValue("fileId", fileId),
                        (rs, row) -> rs.getObject(1, UUID.class))
                .stream().collect(Collectors.toUnmodifiableSet());
    }

    public Set<UUID> findAllStorageKeys() {
        return jdbc.query("SELECT storage_key FROM file_versions", new MapSqlParameterSource(),
                        (rs, row) -> rs.getObject(1, UUID.class))
                .stream().collect(Collectors.toUnmodifiableSet());
    }

    private MapSqlParameterSource parameters(FileVersionRecord version) {
        return new MapSqlParameterSource()
                .addValue("id", version.id())
                .addValue("fileId", version.fileId())
                .addValue("versionNumber", version.versionNumber())
                .addValue("uploaderUserId", version.uploaderUserId())
                .addValue("storageKey", version.storageKey())
                .addValue("sizeBytes", version.sizeBytes())
                .addValue("changeMessage", version.changeMessage());
    }

    private FileVersionRecord mapVersion(ResultSet rs, int row) throws SQLException {
        return new FileVersionRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("file_id", UUID.class),
                rs.getInt("version_number"),
                rs.getObject("uploader_user_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("storage_key", UUID.class),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getString("change_message"),
                rs.getString("status"));
    }
}

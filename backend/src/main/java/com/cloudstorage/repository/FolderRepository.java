package com.cloudstorage.repository;

import com.cloudstorage.model.FolderRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FolderRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public FolderRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public FolderRecord create(UUID id, UUID ownerId, UUID parentId, String name) {
        jdbc.update("""
                INSERT INTO folders (id, owner_id, parent_id, name)
                VALUES (:id, :ownerId, CAST(:parentId AS UUID), :name)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("ownerId", ownerId)
                .addValue("parentId", parentId)
                .addValue("name", name));
        return findOwned(id, ownerId).orElseThrow();
    }

    public Optional<FolderRecord> findOwned(UUID id, UUID ownerId) {
        List<FolderRecord> rows = jdbc.query("""
                SELECT id, owner_id, parent_id, name, created_at, updated_at
                FROM folders WHERE id = :id AND owner_id = :ownerId
                """, new MapSqlParameterSource().addValue("id", id).addValue("ownerId", ownerId),
                this::mapFolder);
        return rows.stream().findFirst();
    }

    public List<FolderRecord> list(UUID ownerId, UUID parentId) {
        return jdbc.query("""
                SELECT id, owner_id, parent_id, name, created_at, updated_at
                FROM folders
                WHERE owner_id = :ownerId
                  AND parent_id IS NOT DISTINCT FROM CAST(:parentId AS UUID)
                ORDER BY LOWER(name), created_at, id
                """, new MapSqlParameterSource()
                .addValue("ownerId", ownerId)
                .addValue("parentId", parentId), this::mapFolder);
    }

    public boolean rename(UUID id, UUID ownerId, String name) {
        return jdbc.update("""
                UPDATE folders SET name = :name, updated_at = NOW()
                WHERE id = :id AND owner_id = :ownerId
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("ownerId", ownerId)
                .addValue("name", name)) == 1;
    }

    public boolean move(UUID id, UUID ownerId, UUID parentId) {
        return jdbc.update("""
                UPDATE folders
                SET parent_id = CAST(:parentId AS UUID), updated_at = NOW()
                WHERE id = :id AND owner_id = :ownerId
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("ownerId", ownerId)
                .addValue("parentId", parentId)) == 1;
    }

    public boolean isSameOrDescendant(UUID ownerId, UUID folderId, UUID possibleDescendantId) {
        Boolean result = jdbc.queryForObject("""
                WITH RECURSIVE descendants(id) AS (
                    SELECT id FROM folders WHERE id = :folderId AND owner_id = :ownerId
                    UNION
                    SELECT child.id
                    FROM folders child
                    JOIN descendants parent ON child.parent_id = parent.id
                    WHERE child.owner_id = :ownerId
                )
                SELECT EXISTS (
                    SELECT 1 FROM descendants WHERE id = :possibleDescendantId
                )
                """, new MapSqlParameterSource()
                .addValue("ownerId", ownerId)
                .addValue("folderId", folderId)
                .addValue("possibleDescendantId", possibleDescendantId), Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    public boolean hasChildrenOrFiles(UUID folderId, UUID ownerId) {
        Boolean result = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM folders WHERE owner_id = :ownerId AND parent_id = :folderId
                    UNION ALL
                    SELECT 1 FROM files WHERE owner_id = :ownerId AND folder_id = :folderId
                )
                """, new MapSqlParameterSource()
                .addValue("ownerId", ownerId)
                .addValue("folderId", folderId), Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    public boolean deleteIfEmpty(UUID id, UUID ownerId) {
        return jdbc.update("""
                DELETE FROM folders AS folder
                WHERE folder.id = :id AND folder.owner_id = :ownerId
                  AND NOT EXISTS (
                      SELECT 1 FROM folders child
                      WHERE child.owner_id = :ownerId AND child.parent_id = folder.id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM files file
                      WHERE file.owner_id = :ownerId AND file.folder_id = folder.id
                  )
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("ownerId", ownerId)) == 1;
    }

    public void lockOwner(UUID ownerId) {
        List<UUID> rows = jdbc.query("SELECT id FROM users WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", ownerId),
                (rs, row) -> rs.getObject("id", UUID.class));
        if (rows.isEmpty()) {
            throw new IllegalStateException("Authenticated user no longer exists");
        }
    }

    private FolderRecord mapFolder(ResultSet rs, int row) throws SQLException {
        return new FolderRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                rs.getObject("parent_id", UUID.class),
                rs.getString("name"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}

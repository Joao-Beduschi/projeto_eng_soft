package com.cloudstorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class FileVersionMigrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("file_version_migration_test")
            .withUsername("migration_test")
            .withPassword("migration-test-password");

    @Test
    void migrationBackfillsExistingReadyFilesWithoutChangingTheirStorageKeyAndIsIdempotent() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID legacyStorageKey = UUID.randomUUID();
        String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        Instant uploadedAt = Instant.parse("2024-03-04T05:06:07Z");

        Flyway flywayAtV1 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("1"))
                .load();
        flywayAtV1.migrate();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (PreparedStatement user = connection.prepareStatement(
                    "INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)");
                 PreparedStatement file = connection.prepareStatement("""
                    INSERT INTO files (id, owner_id, name, storage_key, size_bytes, mime_type,
                                       sha256, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'ready', ?, ?)
                    """)) {
                user.setObject(1, ownerId);
                user.setString(2, "legacy@example.com");
                user.setString(3, "test-only-password-hash");
                user.executeUpdate();

                file.setObject(1, fileId);
                file.setObject(2, ownerId);
                file.setString(3, "legacy.bin");
                file.setObject(4, legacyStorageKey);
                file.setLong(5, 17);
                file.setString(6, "application/octet-stream");
                file.setString(7, hash);
                file.setObject(8, OffsetDateTime.ofInstant(uploadedAt, ZoneOffset.UTC));
                file.setObject(9, OffsetDateTime.ofInstant(uploadedAt, ZoneOffset.UTC));
                file.executeUpdate();
            }
        }

        Flyway latest = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
        latest.migrate();
        latest.migrate();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement backfillRetry = connection.prepareStatement("""
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
                    )
                    """)) {
            assertEquals(0, backfillRetry.executeUpdate());
        }

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement query = connection.prepareStatement("""
                    SELECT v.version_number, v.uploader_user_id, v.created_at, v.storage_key,
                           v.size_bytes, v.sha256, v.change_message, v.status,
                           f.current_version_id = v.id AS is_current,
                           f.storage_key AS file_storage_key
                    FROM file_versions v
                    JOIN files f ON f.id = v.file_id
                    WHERE v.file_id = ?
                    """)) {
            query.setObject(1, fileId);
            try (ResultSet row = query.executeQuery()) {
                assertTrue(row.next());
                assertEquals(1, row.getInt("version_number"));
                assertEquals(ownerId, row.getObject("uploader_user_id", UUID.class));
                assertEquals(uploadedAt, row.getTimestamp("created_at").toInstant());
                assertEquals(legacyStorageKey, row.getObject("storage_key", UUID.class));
                assertEquals(legacyStorageKey, row.getObject("file_storage_key", UUID.class));
                assertEquals(17, row.getLong("size_bytes"));
                assertEquals(hash, row.getString("sha256").trim());
                assertEquals("ready", row.getString("status"));
                assertEquals("Backfilled from the pre-version file record", row.getString("change_message"));
                assertTrue(row.getBoolean("is_current"));
                assertTrue(!row.next(), "The same legacy file must not be backfilled twice");
            }
            try (PreparedStatement count = connection.prepareStatement(
                    "SELECT COUNT(*) FROM file_versions WHERE file_id = ?")) {
                count.setObject(1, fileId);
                try (ResultSet row = count.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals(1, row.getLong(1));
                }
            }
        }
    }
}

package com.cloudstorage.repository;

import com.cloudstorage.model.UserAccount;
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
public class UserRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public UserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UserAccount insert(UUID id, String email, String passwordHash) {
        jdbc.update("""
                INSERT INTO users (id, email, password_hash)
                VALUES (:id, :email, :passwordHash)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("email", email)
                .addValue("passwordHash", passwordHash));
        return findById(id).orElseThrow();
    }

    public Optional<UserAccount> findByEmail(String normalizedEmail) {
        return queryOne("""
                SELECT id, email, password_hash, created_at
                FROM users
                WHERE LOWER(email) = :email
                """, new MapSqlParameterSource("email", normalizedEmail));
    }

    public Optional<UserAccount> findById(UUID id) {
        return queryOne("""
                SELECT id, email, password_hash, created_at
                FROM users
                WHERE id = :id
                """, new MapSqlParameterSource("id", id));
    }

    private Optional<UserAccount> queryOne(String sql, MapSqlParameterSource parameters) {
        List<UserAccount> results = jdbc.query(sql, parameters, this::mapUser);
        return results.stream().findFirst();
    }

    private UserAccount mapUser(ResultSet rs, int row) throws SQLException {
        return new UserAccount(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}

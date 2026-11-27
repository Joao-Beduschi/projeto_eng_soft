package com.cloudstorage.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_change_tokens")
public class EmailChangeToken extends AssignedIdEntity {
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private UserEntity user;

    @Column(name = "new_email", length = 320, nullable = false)
    private String newEmail;

    @Column(name = "token_hash", length = 64, nullable = false)
    private String tokenHash;

    @Column(name = "credential_version", nullable = false)
    private long credentialVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    protected EmailChangeToken() {}

    public EmailChangeToken(UUID userId, String newEmail, String tokenHash, long credentialVersion,
                            Instant createdAt, Instant expiresAt) {
        id = UUID.randomUUID();
        this.userId = userId;
        this.newEmail = newEmail;
        this.tokenHash = tokenHash;
        this.credentialVersion = credentialVersion;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }
}

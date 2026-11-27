package com.cloudstorage.repository.entity;

import com.cloudstorage.model.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.Generated;

@Entity
@Table(name = "users")
public class UserEntity extends AssignedIdEntity {
    @Column(length = 320, nullable = false)
    private String email;

    @Column(name = "password_hash", columnDefinition = "text", nullable = false)
    private String passwordHash;

    @Generated
    @Column(name = "created_at", insertable = false, nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "credential_version", nullable = false)
    private long credentialVersion;

    public long credentialVersion() {
        return credentialVersion;
    }

    public void resetPassword(String encodedPassword) {
        passwordHash = encodedPassword;
        credentialVersion++;
    }

    public void changeEmail(String newEmail) {
        email = newEmail;
        credentialVersion++;
    }

    public void updateDisplayName(String name) {
        displayName = name;
    }

    protected UserEntity() {
    }

    public UserEntity(UserAccount source) {
        id = source.id();
        email = source.email();
        passwordHash = source.passwordHash();
    }

    public UserAccount snapshot() {
        return new UserAccount(id, email, passwordHash, createdAt);
    }
}

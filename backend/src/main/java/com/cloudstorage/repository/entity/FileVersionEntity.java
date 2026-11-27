package com.cloudstorage.repository.entity;

import com.cloudstorage.model.FileVersionRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "file_versions")
public class FileVersionEntity extends AssignedIdEntity {
    @Column(name = "file_id", nullable = false)
    private UUID fileId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "uploader_user_id", nullable = false)
    private UUID uploaderUserId;

    @Generated
    @Column(name = "created_at", insertable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "storage_key", nullable = false)
    private UUID storageKey;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(columnDefinition = "char(64)", length = 64)
    private String sha256;

    @Column(name = "change_message", length = 1000)
    private String changeMessage;

    @Column(length = 16, nullable = false)
    private String status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", insertable = false, updatable = false)
    private FileEntity file;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploader_user_id", insertable = false, updatable = false)
    private UserEntity uploader;

    protected FileVersionEntity() {
    }

    public FileVersionEntity(FileVersionRecord source) {
        id = source.id();
        fileId = source.fileId();
        versionNumber = source.versionNumber();
        uploaderUserId = source.uploaderUserId();
        storageKey = source.storageKey();
        sizeBytes = source.sizeBytes();
        sha256 = source.sha256();
        changeMessage = source.changeMessage();
        status = "pending";
    }

    public FileVersionRecord snapshot() {
        return new FileVersionRecord(id, fileId, versionNumber, uploaderUserId, createdAt, storageKey,
                sizeBytes, sha256, changeMessage, status);
    }
}

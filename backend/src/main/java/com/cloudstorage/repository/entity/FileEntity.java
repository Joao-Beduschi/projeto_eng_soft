package com.cloudstorage.repository.entity;

import com.cloudstorage.model.FileRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "files")
public class FileEntity extends AssignedIdEntity {
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "folder_id")
    private UUID folderId;

    @Column(nullable = false)
    private String name;

    @Column(name = "storage_key", nullable = false)
    private UUID storageKey;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(columnDefinition = "char(64)", length = 64)
    private String sha256;

    @Column(length = 16, nullable = false)
    private String status;

    @Column(name = "current_version_id")
    private UUID currentVersionId;

    @Generated
    @Column(name = "created_at", insertable = false, nullable = false)
    private OffsetDateTime createdAt;

    @Generated
    @Column(name = "updated_at", insertable = false, nullable = false)
    private OffsetDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", insertable = false, updatable = false)
    private UserEntity owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id", insertable = false, updatable = false)
    private FolderEntity folder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_version_id", insertable = false, updatable = false)
    private FileVersionEntity currentVersion;

    protected FileEntity() {
    }

    public FileEntity(FileRecord source) {
        id = source.id();
        ownerId = source.ownerId();
        folderId = source.folderId();
        name = source.name();
        storageKey = source.storageKey();
        sizeBytes = source.sizeBytes();
        mimeType = source.mimeType();
        sha256 = source.sha256();
        status = "pending";
        currentVersionId = source.currentVersionId();
    }

    public FileRecord snapshot() {
        return new FileRecord(id, ownerId, folderId, name, storageKey, sizeBytes, mimeType, sha256, status,
                currentVersionId, createdAt, updatedAt);
    }
}

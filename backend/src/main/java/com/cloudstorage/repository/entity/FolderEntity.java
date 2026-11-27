package com.cloudstorage.repository.entity;

import com.cloudstorage.model.FolderRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;

@Entity
@Table(name = "folders")
public class FolderEntity extends AssignedIdEntity {
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(nullable = false)
    private String name;

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
    @JoinColumn(name = "parent_id", insertable = false, updatable = false)
    private FolderEntity parent;

    protected FolderEntity() {
    }

    public FolderEntity(FolderRecord source) {
        id = source.id();
        ownerId = source.ownerId();
        parentId = source.parentId();
        name = source.name();
    }

    public FolderRecord snapshot() {
        return new FolderRecord(id, ownerId, parentId, name, createdAt, updatedAt);
    }
}

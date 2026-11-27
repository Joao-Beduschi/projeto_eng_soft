package com.cloudstorage.repository.entity;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@MappedSuperclass
public abstract class AssignedIdEntity implements Persistable<UUID> {
    @Id
    protected UUID id;

    // UUIDs are allocated before insertion. Keep save() on the persist path for new rows.
    @Transient
    private boolean newEntity = true;

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    private void markPersisted() {
        newEntity = false;
    }
}

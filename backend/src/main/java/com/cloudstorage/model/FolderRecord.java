package com.cloudstorage.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FolderRecord(
        UUID id,
        UUID ownerId,
        UUID parentId,
        String name,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}

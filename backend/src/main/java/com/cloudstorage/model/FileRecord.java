package com.cloudstorage.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FileRecord(
        UUID id,
        UUID ownerId,
        UUID folderId,
        String name,
        UUID storageKey,
        long sizeBytes,
        String mimeType,
        String sha256,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}

package com.cloudstorage.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FileResponse(
        UUID id,
        String name,
        long sizeBytes,
        String mimeType,
        String sha256,
        UUID folderId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}

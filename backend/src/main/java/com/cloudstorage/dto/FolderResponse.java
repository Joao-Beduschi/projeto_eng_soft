package com.cloudstorage.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FolderResponse(
        UUID id,
        String name,
        UUID parentId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}

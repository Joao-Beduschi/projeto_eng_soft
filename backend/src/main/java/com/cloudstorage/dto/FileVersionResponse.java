package com.cloudstorage.dto;

import java.time.Instant;
import java.util.UUID;

public record FileVersionResponse(
        UUID id,
        UUID fileId,
        int versionNumber,
        UUID uploaderUserId,
        Instant createdAt,
        long sizeBytes,
        String sha256,
        String changeMessage,
        boolean current) {
}

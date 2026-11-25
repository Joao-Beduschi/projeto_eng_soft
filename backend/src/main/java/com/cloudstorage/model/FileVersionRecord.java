package com.cloudstorage.model;

import java.time.Instant;
import java.util.UUID;

public record FileVersionRecord(
        UUID id,
        UUID fileId,
        int versionNumber,
        UUID uploaderUserId,
        Instant createdAt,
        UUID storageKey,
        long sizeBytes,
        String sha256,
        String changeMessage,
        String status) {
}

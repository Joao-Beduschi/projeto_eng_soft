package com.cloudstorage.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserResponse(UUID id, String email, OffsetDateTime createdAt) {
}

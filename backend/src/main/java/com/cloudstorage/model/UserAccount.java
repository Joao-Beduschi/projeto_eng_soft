package com.cloudstorage.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserAccount(UUID id, String email, String passwordHash, OffsetDateTime createdAt) {
}

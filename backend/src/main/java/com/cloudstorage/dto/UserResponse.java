package com.cloudstorage.dto;

import com.cloudstorage.model.UserAccount;
import java.time.OffsetDateTime;
import java.util.UUID;

public record UserResponse(UUID id, String email, OffsetDateTime createdAt) {
    public static UserResponse from(UserAccount user) {
        return new UserResponse(user.id(), user.email(), user.createdAt());
    }
}

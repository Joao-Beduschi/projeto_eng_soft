package com.cloudstorage.dto;

import java.time.OffsetDateTime;

public record UserProfileResponse(String displayName, String email, OffsetDateTime createdAt) {}

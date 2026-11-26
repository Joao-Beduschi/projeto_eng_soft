package com.cloudstorage.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthRequests {
    private static final String EMAIL_PATTERN =
            "^(?=[^@]{1,64}@[^@]{1,255}$)[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$";

    private AuthRequests() {
    }

    public record RegisterRequest(
            @NotBlank @Email @Pattern(regexp = EMAIL_PATTERN) @Size(max = 320) String email,
            @NotBlank @Size(min = 12, max = 128) String password) {
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 128) String password) {
    }
}

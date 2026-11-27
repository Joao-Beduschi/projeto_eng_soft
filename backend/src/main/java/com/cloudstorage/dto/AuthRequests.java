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
            @NotBlank(message = "{validation.email.required}")
            @Email(message = "{validation.email.invalid}")
            @Pattern(regexp = EMAIL_PATTERN, message = "{validation.email.invalid}")
            @Size(max = 320, message = "{validation.email.length}") String email,
            @NotBlank(message = "{validation.password.required}")
            @Size(min = 12, max = 128, message = "{validation.password.register_length}") String password) {
    }

    public record LoginRequest(
            @NotBlank(message = "{validation.email.required}")
            @Email(message = "{validation.email.invalid}")
            @Size(max = 320, message = "{validation.email.length}") String email,
            @NotBlank(message = "{validation.password.required}")
            @Size(max = 128, message = "{validation.password.login_length}") String password) {
    }
}

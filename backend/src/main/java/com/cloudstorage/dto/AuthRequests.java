package com.cloudstorage.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthRequests {
    private static final String EMAIL_PATTERN =
            "^(?=[^@]{1,64}@[^@]{1,255}$)[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$";

    private static final int PASSWORD_MIN = 12;
    private static final int PASSWORD_MAX = 128;

    private AuthRequests() {
    }

    public record RegisterRequest(
            @NotBlank(message = "{validation.email.required}")
            @Email(message = "{validation.email.invalid}")
            @Pattern(regexp = EMAIL_PATTERN, message = "{validation.email.invalid}")
            @Size(max = 320, message = "{validation.email.length}") String email,
            @NotBlank(message = "{validation.password.required}")
            @Size(min = PASSWORD_MIN, max = PASSWORD_MAX, message = "{validation.password.register_length}") String password) {
    }

    public record ForgotPasswordRequest(
            @NotBlank(message = "{validation.email.required}")
            @Email(message = "{validation.email.invalid}")
            @Pattern(regexp = EMAIL_PATTERN, message = "{validation.email.invalid}")
            @Size(max = 320, message = "{validation.email.length}") String email) {
    }

    public record ResetPasswordRequest(
            @NotBlank(message = "{validation.password.required}")
            @Size(min = PASSWORD_MIN, max = PASSWORD_MAX, message = "{validation.password.register_length}") String password,
            @NotBlank(message = "{auth.reset.confirm_required}")
            @Size(max = PASSWORD_MAX, message = "{validation.password.login_length}") String confirmation) {
    }

    public record LoginRequest(
            @NotBlank(message = "{validation.email.required}")
            @Email(message = "{validation.email.invalid}")
            @Size(max = 320, message = "{validation.email.length}") String email,
            @NotBlank(message = "{validation.password.required}")
            @Size(max = PASSWORD_MAX, message = "{validation.password.login_length}") String password) {
    }
}

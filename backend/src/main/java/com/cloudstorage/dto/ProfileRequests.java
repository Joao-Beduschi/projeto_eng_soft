package com.cloudstorage.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ProfileRequests {
    private ProfileRequests() {}

    public record UpdateProfileRequest(
            @Size(max = 100, message = "{profile.name.length}")
            @Pattern(regexp = "[^\\p{Cc}]*", message = "{profile.name.invalid}")
            String displayName) {}

    public record ChangeEmailRequest(
            @NotBlank(message = "{validation.email.required}")
            @Email(message = "{validation.email.invalid}")
            @Pattern(regexp = AuthRequests.EMAIL_PATTERN, message = "{validation.email.invalid}")
            @Size(max = 320, message = "{validation.email.length}") String newEmail,
            @NotBlank(message = "{profile.password.current_required}")
            @Size(max = AuthRequests.PASSWORD_MAX, message = "{validation.password.login_length}")
            String currentPassword) {
        @Override
        public String toString() {
            return "ChangeEmailRequest[redacted]";
        }
    }

    public record ChangePasswordRequest(
            @NotBlank(message = "{profile.password.current_required}")
            @Size(max = AuthRequests.PASSWORD_MAX, message = "{validation.password.login_length}")
            String currentPassword,
            AuthRequests.ResetPasswordRequest replacement) {
        @Override
        public String toString() {
            return "ChangePasswordRequest[redacted]";
        }
    }
}

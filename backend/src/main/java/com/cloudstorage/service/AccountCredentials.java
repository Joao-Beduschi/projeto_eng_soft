package com.cloudstorage.service;

import com.cloudstorage.dto.AuthRequests.ResetPasswordRequest;
import com.cloudstorage.exception.ApiException;
import com.cloudstorage.repository.EmailChangeTokenRepository;
import com.cloudstorage.repository.PasswordResetTokenRepository;
import com.cloudstorage.repository.entity.UserEntity;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
class AccountCredentials {
    private final PasswordEncoder encoder;
    private final Validator validator;
    private final PasswordResetTokenRepository resetTokens;
    private final EmailChangeTokenRepository emailTokens;

    @Autowired
    AccountCredentials(PasswordEncoder encoder, Validator validator, PasswordResetTokenRepository resetTokens,
                       EmailChangeTokenRepository emailTokens) {
        this.encoder = encoder;
        this.validator = validator;
        this.resetTokens = resetTokens;
        this.emailTokens = emailTokens;
    }

    void verifyCurrent(UserEntity account, String currentPassword) {
        if (!encoder.matches(currentPassword, account.snapshot().passwordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "current_password_incorrect", "profile.password.incorrect");
        }
    }

    void validateReplacement(ResetPasswordRequest form) {
        var violations = validator.validate(form);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        if (!form.password().equals(form.confirmation())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "password_confirmation_mismatch", "auth.reset.mismatch");
        }
    }

    // Call within the transaction that holds the user row lock.
    void changePassword(UserEntity account, String newPassword, Instant now) {
        account.resetPassword(encoder.encode(newPassword));
        invalidateTokens(account, now);
    }

    void changeEmail(UserEntity account, String newEmail, Instant now) {
        account.changeEmail(newEmail);
        invalidateTokens(account, now);
    }

    private void invalidateTokens(UserEntity account, Instant now) {
        resetTokens.invalidateOutstanding(account.getId(), now);
        emailTokens.invalidateOutstanding(account.getId(), now);
    }
}

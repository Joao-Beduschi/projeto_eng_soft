package com.cloudstorage.service;

import com.cloudstorage.config.PasswordRecoveryConfig.Settings;
import com.cloudstorage.dto.ProfileRequests.ChangeEmailRequest;
import com.cloudstorage.dto.ProfileRequests.ChangePasswordRequest;
import com.cloudstorage.dto.ProfileRequests.UpdateProfileRequest;
import com.cloudstorage.dto.UserProfileResponse;
import com.cloudstorage.exception.ApiException;
import com.cloudstorage.repository.DatabaseConstraints;
import com.cloudstorage.repository.EmailChangeTokenRepository;
import com.cloudstorage.repository.UserRepository;
import com.cloudstorage.repository.entity.EmailChangeToken;
import com.cloudstorage.repository.entity.UserEntity;
import com.cloudstorage.security.UserPrincipal;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UserProfileService {
    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);
    private static final int MAX_SENSITIVE_ATTEMPTS = 10;
    private static final int MAX_TRACKED_ACCOUNTS = 4096;
    private static final int ATTEMPT_WINDOW_SECONDS = 600;

    private final UserRepository users;
    private final EmailChangeTokenRepository emailTokens;
    private final AccountCredentials credentials;
    private final AccountMail mail;
    private final Settings settings;
    private final Validator validator;
    private final Clock clock;
    private final TaskExecutor executor;
    private final TransactionTemplate transactions;
    private final int expirationMinutes;
    private final Map<UUID, AttemptWindow> attempts = new HashMap<>();

    @Autowired
    public UserProfileService(UserRepository users, EmailChangeTokenRepository emailTokens,
                              AccountCredentials credentials, AccountMail mail, Settings settings, Validator validator,
                              Clock clock, @Qualifier("passwordRecoveryExecutor") TaskExecutor executor,
                              TransactionTemplate transactions,
                              @Value("${app.profile.email-expiration-minutes}") int expirationMinutes) {
        if (expirationMinutes < 1 || expirationMinutes > 1440) {
            throw new IllegalArgumentException("Invalid email change expiration");
        }
        this.users = users;
        this.emailTokens = emailTokens;
        this.credentials = credentials;
        this.mail = mail;
        this.settings = settings;
        this.validator = validator;
        this.clock = clock;
        this.executor = executor;
        this.transactions = transactions;
        this.expirationMinutes = expirationMinutes;
    }

    public UserProfileResponse getProfile(UserPrincipal user) {
        return users.findProfile(user.id())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "user_not_found"));
    }

    public void updateDisplayName(UserPrincipal user, UpdateProfileRequest form) {
        validate(form);
        String name = form.displayName() == null ? "" : form.displayName().strip();
        transactions.executeWithoutResult(status -> lockCurrent(user).updateDisplayName(name.isEmpty() ? null : name));
    }

    public void requestEmailChange(UserPrincipal user, ChangeEmailRequest form, Locale locale) {
        validate(form);
        if (!mail.isEnabled()) {
            throw unavailable();
        }
        limitAttempts(user.id());
        String newEmail = AuthService.normalizeEmail(form.newEmail());
        String token = AccountToken.generate();
        transactions.executeWithoutResult(status -> {
            UserEntity account = lockCurrent(user);
            credentials.verifyCurrent(account, form.currentPassword());
            if (AuthService.normalizeEmail(account.snapshot().email()).equals(newEmail)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "profile_email_unchanged", "profile.email.unchanged");
            }
            requireAvailable(newEmail);
            Instant now = clock.instant();
            if (emailTokens.existsByUserIdAndCreatedAtAfter(user.id(), now.minusSeconds(settings.minIntervalSeconds()))
                    || emailTokens.countByUserIdAndCreatedAtAfter(user.id(), now.minus(1, ChronoUnit.HOURS))
                    >= settings.perAccountPerHour()) {
                throw throttled();
            }
            emailTokens.save(new EmailChangeToken(user.id(), newEmail, AccountToken.hash(token),
                    account.credentialVersion(), now, now.plus(expirationMinutes, ChronoUnit.MINUTES)));
        });
        // The worker receives the one-time secret only after the database commits its hash.
        if (!enqueueMail(() -> mail.sendEmailConfirmation(newEmail, token, expirationMinutes, locale))) {
            throw unavailable();
        }
    }

    public Optional<Confirmation> prepareConfirmation(UserPrincipal user, String token) {
        if (!AccountToken.hasValidFormat(token)) {
            return Optional.empty();
        }
        String digest = AccountToken.hash(token);
        return emailTokens.findActiveAddress(digest, user.id(), user.credentialVersion(), clock.instant())
                .map(address -> new Confirmation(digest, address));
    }

    public void confirmEmailChange(UserPrincipal user, Confirmation confirmation, Locale locale) {
        String oldAddress;
        try {
            oldAddress = transactions.execute(status -> {
                UserEntity account = lockCurrent(user);
                Instant now = clock.instant();
                String newEmail = emailTokens.findActiveAddress(confirmation.digest(), user.id(),
                        account.credentialVersion(), now).orElseThrow(UserProfileService::invalidLink);
                requireAvailable(newEmail);
                if (emailTokens.consume(confirmation.digest(), user.id(), account.credentialVersion(), now) != 1) {
                    throw invalidLink();
                }
                String previousEmail = account.snapshot().email();
                credentials.changeEmail(account, newEmail, now);
                return previousEmail;
            });
        } catch (DataIntegrityViolationException failure) {
            if (DatabaseConstraints.isUniqueViolation(failure, "users_email_unique")) {
                throw emailTaken();
            }
            throw failure;
        }
        if (mail.isEnabled()) {
            enqueueMail(() -> mail.sendEmailChangedNotice(oldAddress, locale));
        }
    }

    public void changePassword(UserPrincipal user, ChangePasswordRequest form) {
        validate(form);
        credentials.validateReplacement(form.replacement());
        limitAttempts(user.id());
        transactions.executeWithoutResult(status -> {
            UserEntity account = lockCurrent(user);
            credentials.verifyCurrent(account, form.currentPassword());
            credentials.changePassword(account, form.replacement().password(), clock.instant());
        });
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void cleanup() {
        Instant now = clock.instant();
        emailTokens.purgeObsolete(now, now.minusSeconds(Math.max(3600, settings.minIntervalSeconds())));
        synchronized (this) {
            removeExpiredAttempts(now);
        }
    }

    private boolean enqueueMail(Runnable send) {
        try {
            executor.execute(() -> {
                try {
                    send.run();
                } catch (RuntimeException exception) {
                    log.warn("Account change email delivery failed");
                }
            });
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    private synchronized void limitAttempts(UUID userId) {
        Instant now = clock.instant();
        AttemptWindow window = attempts.get(userId);
        if (window == null || !window.expiresAt().isAfter(now)) {
            removeExpiredAttempts(now);
            if (attempts.size() >= MAX_TRACKED_ACCOUNTS) {
                throw throttled();
            }
            window = new AttemptWindow(now.plusSeconds(ATTEMPT_WINDOW_SECONDS), 0);
        }
        if (window.count() >= MAX_SENSITIVE_ATTEMPTS) {
            throw throttled();
        }
        attempts.put(userId, new AttemptWindow(window.expiresAt(), window.count() + 1));
    }

    private void removeExpiredAttempts(Instant now) {
        attempts.values().removeIf(window -> !window.expiresAt().isAfter(now));
    }

    private void validate(Object form) {
        var violations = validator.validate(form);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    private UserEntity lockCurrent(UserPrincipal user) {
        return users.findForUpdate(user.id())
                .filter(account -> account.credentialVersion() == user.credentialVersion())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "invalid_session"));
    }

    private void requireAvailable(String email) {
        if (users.findByNormalizedEmail(email).isPresent()) {
            throw emailTaken();
        }
    }

    private static ApiException emailTaken() {
        return new ApiException(HttpStatus.CONFLICT, "profile_email_taken", "profile.email.taken");
    }

    private static ApiException invalidLink() {
        return new ApiException(HttpStatus.BAD_REQUEST, "profile_email_invalid_link", "profile.email.invalid_link");
    }

    private static ApiException throttled() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "profile_rate_limit", "profile.rate_limit");
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "profile_email_unavailable", "profile.email.unavailable");
    }

    public record Confirmation(String digest, String email) {
        @Override
        public String toString() {
            return "Confirmation[redacted]";
        }
    }

    private record AttemptWindow(Instant expiresAt, int count) {}
}

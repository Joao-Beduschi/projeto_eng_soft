package com.cloudstorage.service;

import com.cloudstorage.config.PasswordRecoveryConfig.Settings;
import com.cloudstorage.dto.AuthRequests.ResetPasswordRequest;
import com.cloudstorage.exception.ApiException;
import com.cloudstorage.model.UserAccount;
import com.cloudstorage.repository.PasswordResetTokenRepository;
import com.cloudstorage.repository.UserRepository;
import com.cloudstorage.repository.entity.PasswordResetToken;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class PasswordResetService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final Validator validator;
    private final PasswordResetMail mail;
    private final Settings settings;
    private final Clock clock;
    private final TaskExecutor executor;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();

    private long requestMinute = Long.MIN_VALUE;
    private int requestsThisMinute;

    @Autowired
    public PasswordResetService(UserRepository users, PasswordResetTokenRepository tokens,
                                PasswordEncoder passwordEncoder, Validator validator, PasswordResetMail mail,
                                Settings settings, Clock clock,
                                @Qualifier("passwordRecoveryExecutor") TaskExecutor executor,
                                TransactionTemplate transactions) {
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.validator = validator;
        this.mail = mail;
        this.settings = settings;
        this.clock = clock;
        this.executor = executor;
        this.transactions = transactions;
    }

    public void request(String email, Locale locale) {
        if (!mail.isEnabled() || !allowRequest()) {
            return;
        }
        String normalizedEmail = AuthService.normalizeEmail(email);
        try {
            executor.execute(() -> issueAndSend(normalizedEmail, locale));
        } catch (RejectedExecutionException ignored) {
            // Keep the same public confirmation when the queue is full.
        }
    }

    private synchronized boolean allowRequest() {
        long minute = clock.instant().getEpochSecond() / 60;
        if (minute != requestMinute) {
            requestMinute = minute;
            requestsThisMinute = 0;
        }
        if (requestsThisMinute >= settings.globalPerMinute()) {
            return false;
        }
        requestsThisMinute++;
        return true;
    }

    private void issueAndSend(String email, Locale locale) {
        try {
            UserAccount account = users.findByEmail(email).orElse(null);
            if (account == null) {
                return;
            }
            byte[] randomBytes = new byte[32];
            random.nextBytes(randomBytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
            boolean issued = Boolean.TRUE.equals(transactions.execute(status -> {
                if (users.findForUpdate(account.id()).isEmpty()) {
                    return false;
                }
                Instant now = clock.instant();
                if (tokens.existsByUserIdAndCreatedAtAfter(account.id(), now.minusSeconds(settings.minIntervalSeconds()))
                        || tokens.countByUserIdAndCreatedAtAfter(account.id(), now.minus(1, ChronoUnit.HOURS))
                        >= settings.perAccountPerHour()) {
                    return false;
                }
                tokens.save(new PasswordResetToken(account.id(), hash(token), now,
                        now.plus(settings.expirationMinutes(), ChronoUnit.MINUTES)));
                return true;
            }));
            if (issued) {
                String link = UriComponentsBuilder.fromUri(settings.baseUrl()).pathSegment("reset-password")
                        .queryParam("token", token).queryParam("lang", locale.toLanguageTag())
                        .build().encode().toUriString();
                // The token transaction has committed before any SMTP work begins.
                mail.send(account.email(), link, locale);
            }
        } catch (MailException exception) {
            log.warn("Password reset email delivery failed");
        } catch (RuntimeException exception) {
            log.warn("Password reset request could not be processed");
        }
    }

    public boolean isValid(String token) {
        return validFormat(token) && tokens.findActiveUser(hash(token), clock.instant()).isPresent();
    }

    public boolean reset(String token, ResetPasswordRequest form) {
        if (!validFormat(token)) {
            return false;
        }
        String hash = hash(token);
        UUID owner = tokens.findActiveUser(hash, clock.instant()).orElse(null);
        if (owner == null) {
            return false;
        }
        var violations = validator.validate(form);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        if (!form.password().equals(form.confirmation())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "password_confirmation_mismatch", "auth.reset.mismatch");
        }

        return Boolean.TRUE.equals(transactions.execute(status -> {
            var account = users.findForUpdate(owner).orElse(null);
            Instant now = clock.instant();
            if (account == null || tokens.consume(hash, owner, now) != 1) {
                return false;
            }
            account.resetPassword(passwordEncoder.encode(form.password()));
            tokens.invalidateOutstanding(owner, now);
            return true;
        }));
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void cleanup() {
        Instant now = clock.instant();
        tokens.purgeObsolete(now, now.minusSeconds(Math.max(3600, settings.minIntervalSeconds())));
    }

    private static boolean validFormat(String token) {
        return token != null && TOKEN_PATTERN.matcher(token).matches();
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

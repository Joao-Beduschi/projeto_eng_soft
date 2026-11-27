package com.cloudstorage.service;

import com.cloudstorage.config.PasswordRecoveryConfig.Settings;
import com.cloudstorage.dto.AuthRequests.ResetPasswordRequest;
import com.cloudstorage.model.UserAccount;
import com.cloudstorage.repository.PasswordResetTokenRepository;
import com.cloudstorage.repository.UserRepository;
import com.cloudstorage.repository.entity.PasswordResetToken;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PasswordResetService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final AccountCredentials credentials;
    private final AccountMail mail;
    private final Settings settings;
    private final Clock clock;
    private final TaskExecutor executor;
    private final TransactionTemplate transactions;

    private long requestMinute = Long.MIN_VALUE;
    private int requestsThisMinute;

    @Autowired
    public PasswordResetService(UserRepository users, PasswordResetTokenRepository tokens,
                                AccountCredentials credentials, AccountMail mail,
                                Settings settings, Clock clock,
                                @Qualifier("passwordRecoveryExecutor") TaskExecutor executor,
                                TransactionTemplate transactions) {
        this.users = users;
        this.tokens = tokens;
        this.credentials = credentials;
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
            String token = AccountToken.generate();
            boolean issued = Boolean.TRUE.equals(transactions.execute(status -> {
                var locked = users.findForUpdate(account.id()).orElse(null);
                // A queued request for the former address must not issue a token after an email change.
                if (locked == null || !AuthService.normalizeEmail(locked.snapshot().email()).equals(email)) {
                    return false;
                }
                Instant now = clock.instant();
                if (tokens.existsByUserIdAndCreatedAtAfter(account.id(), now.minusSeconds(settings.minIntervalSeconds()))
                        || tokens.countByUserIdAndCreatedAtAfter(account.id(), now.minus(1, ChronoUnit.HOURS))
                        >= settings.perAccountPerHour()) {
                    return false;
                }
                tokens.save(new PasswordResetToken(account.id(), AccountToken.hash(token), now,
                        now.plus(settings.expirationMinutes(), ChronoUnit.MINUTES)));
                return true;
            }));
            if (issued) {
                // The token transaction has committed before any SMTP work begins.
                mail.sendReset(account.email(), token, locale);
            }
        } catch (MailException exception) {
            log.warn("Password reset email delivery failed");
        } catch (RuntimeException exception) {
            log.warn("Password reset request could not be processed");
        }
    }

    public boolean isValid(String token) {
        return AccountToken.hasValidFormat(token) && tokens.findActiveUser(AccountToken.hash(token), clock.instant()).isPresent();
    }

    public boolean reset(String token, ResetPasswordRequest form) {
        if (!AccountToken.hasValidFormat(token)) {
            return false;
        }
        String hash = AccountToken.hash(token);
        UUID owner = tokens.findActiveUser(hash, clock.instant()).orElse(null);
        if (owner == null) {
            return false;
        }
        credentials.validateReplacement(form);

        return Boolean.TRUE.equals(transactions.execute(status -> {
            var account = users.findForUpdate(owner).orElse(null);
            Instant now = clock.instant();
            if (account == null || tokens.consume(hash, owner, now) != 1) {
                return false;
            }
            credentials.changePassword(account, form.password(), now);
            return true;
        }));
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void cleanup() {
        Instant now = clock.instant();
        tokens.purgeObsolete(now, now.minusSeconds(Math.max(3600, settings.minIntervalSeconds())));
    }
}

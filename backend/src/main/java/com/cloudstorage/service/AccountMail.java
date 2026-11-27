package com.cloudstorage.service;

import com.cloudstorage.config.PasswordRecoveryConfig;
import com.cloudstorage.config.PasswordRecoveryConfig.Settings;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class AccountMail {
    private final JavaMailSender sender;
    private final Settings settings;
    private final MessageSource messages;
    private final boolean enabled;

    @Autowired
    public AccountMail(ObjectProvider<JavaMailSender> senders, Settings settings, MessageSource messages,
                       @Value("${spring.mail.host:}") String host,
                       @Value("${spring.mail.username:}") String username,
                       @Value("${spring.mail.password:}") String password,
                       @Value("${spring.mail.properties[mail.smtp.auth]}") boolean auth,
                       @Value("${spring.mail.properties[mail.smtp.starttls.required]}") boolean tlsRequired) {
        this.sender = senders.getIfAvailable();
        this.settings = settings;
        this.messages = messages;
        this.enabled = !host.isBlank() && !settings.from().isBlank() && sender != null;
        boolean localDevelopment = PasswordRecoveryConfig.isLoopback(settings.baseUrl().getHost())
                && PasswordRecoveryConfig.isLoopback(host);
        if (enabled && !localDevelopment
                && (!auth || !tlsRequired || username.isBlank() || password.isBlank())) {
            throw new IllegalArgumentException("Account email SMTP requires authentication and STARTTLS");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void sendReset(String recipient, String token, Locale locale) {
        String link = link(token, locale, "reset-password");
        send(recipient, locale, "mail.reset", link, settings.expirationMinutes());
    }

    public void sendEmailConfirmation(String recipient, String token, int expirationMinutes, Locale locale) {
        String link = link(token, locale, "profile", "email", "confirm");
        send(recipient, locale, "mail.email_change", link, expirationMinutes);
    }

    public void sendEmailChangedNotice(String formerAddress, Locale locale) {
        send(formerAddress, locale, "mail.email_changed");
    }

    private String link(String token, Locale locale, String... path) {
        return UriComponentsBuilder.fromUri(settings.baseUrl()).pathSegment(path)
                .queryParam("token", token).queryParam("lang", locale.toLanguageTag())
                .build().encode().toUriString();
    }

    private void send(String recipient, Locale locale, String key, Object... arguments) {
        SimpleMailMessage email = new SimpleMailMessage();
        email.setFrom(settings.from());
        email.setTo(recipient);
        email.setSubject(messages.getMessage(key + ".subject", null, locale));
        email.setText(messages.getMessage(key + ".body", arguments, locale));
        sender.send(email);
    }
}

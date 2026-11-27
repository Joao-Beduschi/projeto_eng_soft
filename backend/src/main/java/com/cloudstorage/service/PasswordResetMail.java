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

@Service
public class PasswordResetMail {
    private final JavaMailSender sender;
    private final Settings settings;
    private final MessageSource messages;
    private final boolean enabled;

    @Autowired
    public PasswordResetMail(ObjectProvider<JavaMailSender> senders, Settings settings, MessageSource messages,
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
            throw new IllegalArgumentException("Password recovery SMTP requires authentication and STARTTLS");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void send(String recipient, String link, Locale locale) {
        SimpleMailMessage email = new SimpleMailMessage();
        email.setFrom(settings.from());
        email.setTo(recipient);
        email.setSubject(messages.getMessage("mail.reset.subject", null, locale));
        email.setText(messages.getMessage("mail.reset.body", new Object[]{link, settings.expirationMinutes()}, locale));
        sender.send(email);
    }
}

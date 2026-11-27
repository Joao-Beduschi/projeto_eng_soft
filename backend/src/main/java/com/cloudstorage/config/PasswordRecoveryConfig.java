package com.cloudstorage.config;

import java.net.URI;
import java.time.Clock;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(PasswordRecoveryConfig.Settings.class)
public class PasswordRecoveryConfig {
    @Bean
    Clock recoveryClock() {
        return Clock.systemUTC();
    }

    @Bean
    ThreadPoolTaskExecutor passwordRecoveryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("password-recovery-");
        return executor;
    }

    public static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host);
    }

    @ConfigurationProperties("app.recovery")
    public record Settings(URI baseUrl, int expirationMinutes, int minIntervalSeconds,
                           int perAccountPerHour, int globalPerMinute, String from) {
        public Settings {
            if (baseUrl == null || baseUrl.getHost() == null || baseUrl.getUserInfo() != null
                    || baseUrl.getQuery() != null || baseUrl.getFragment() != null
                    || !("https".equalsIgnoreCase(baseUrl.getScheme())
                    || ("http".equalsIgnoreCase(baseUrl.getScheme()) && isLoopback(baseUrl.getHost())))) {
                throw new IllegalArgumentException("APP_BASE_URL requires HTTPS except on loopback");
            }
            if (expirationMinutes < 1 || expirationMinutes > 1440 || minIntervalSeconds < 0
                    || perAccountPerHour < 1 || globalPerMinute < 1) {
                throw new IllegalArgumentException("Invalid password recovery limits");
            }
        }
    }
}

package com.cloudstorage.config;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

@Configuration
public class LocaleConfig implements WebMvcConfigurer {
    private static final Locale PORTUGUESE = Locale.forLanguageTag("pt-BR");
    private static final List<Locale> SUPPORTED = List.of(PORTUGUESE, Locale.ENGLISH);

    @Bean
    LocaleResolver localeResolver(@Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
        CookieLocaleResolver resolver = new CookieLocaleResolver("CLOUD_STORAGE_LOCALE") {
            @Override
            protected Locale parseLocaleValue(String value) {
                return supportedLocale(value);
            }
        };
        resolver.setRejectInvalidCookies(false);
        resolver.setDefaultLocaleFunction(LocaleConfig::preferredLocale);
        resolver.setCookieMaxAge(Duration.ofDays(365));
        resolver.setCookieSameSite("Lax");
        resolver.setCookieHttpOnly(true);
        resolver.setCookieSecure(secure);
        return resolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        LocaleChangeInterceptor languageSwitch = new LocaleChangeInterceptor() {
            @Override
            protected Locale parseLocaleValue(String value) {
                return supportedLocale(value);
            }
        };
        languageSwitch.setParamName("lang");
        languageSwitch.setHttpMethods("GET");
        languageSwitch.setIgnoreInvalidLocale(true);
        registry.addInterceptor(languageSwitch)
                .addPathPatterns("/", "/login", "/register", "/files", "/folders/{folderId}");
    }

    private static Locale supportedLocale(String value) {
        return switch (value) {
            case "pt-BR" -> PORTUGUESE;
            case "en" -> Locale.ENGLISH;
            default -> throw new IllegalArgumentException("Unsupported language");
        };
    }

    private static Locale preferredLocale(HttpServletRequest request) {
        String header = request.getHeader("Accept-Language");
        if (header == null) {
            return PORTUGUESE;
        }
        try {
            for (Locale.LanguageRange range : Locale.LanguageRange.parse(header)) {
                if (range.getWeight() == 0) {
                    continue;
                }
                Locale requested = Locale.forLanguageTag(range.getRange());
                for (Locale supported : SUPPORTED) {
                    if (supported.getLanguage().equals(requested.getLanguage())) {
                        return supported;
                    }
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Malformed client language headers use the application fallback.
        }
        return PORTUGUESE;
    }
}

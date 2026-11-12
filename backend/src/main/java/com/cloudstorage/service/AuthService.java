package com.cloudstorage.service;

import com.cloudstorage.exception.ApiException;
import com.cloudstorage.model.UserAccount;
import com.cloudstorage.model.UserPrincipal;
import com.cloudstorage.repository.UserRepository;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    public UserAccount register(String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        try {
            return users.insert(UUID.randomUUID(), normalizedEmail, passwordEncoder.encode(password));
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "email_already_registered",
                    "An account with this email already exists.");
        }
    }

    public UserDetailsService userDetailsService() {
        return email -> users.findByEmail(normalizeEmail(email))
                .map(user -> new UserPrincipal(user.id(), user.email(), user.passwordHash()))
                .orElseThrow(() -> new UsernameNotFoundException("Invalid email or password"));
    }

    public UserAccount getById(UUID id) {
        return users.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "user_not_found", "User not found."));
    }

    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}

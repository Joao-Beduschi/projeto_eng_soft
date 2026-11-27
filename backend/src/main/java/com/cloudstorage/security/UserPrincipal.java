package com.cloudstorage.security;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class UserPrincipal implements UserDetails, CredentialsContainer {
    private final UUID id;
    private final String email;
    private final long credentialVersion;
    private String passwordHash;

    public UserPrincipal(UUID id, String email, String passwordHash, long credentialVersion) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.credentialVersion = credentialVersion;
    }

    public UUID id() {
        return id;
    }

    public long credentialVersion() {
        return credentialVersion;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }
}

package com.cloudstorage.security;

import com.cloudstorage.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

final class CredentialVersionFilter extends OncePerRequestFilter {
    private final UserRepository users;
    private final SecurityContextLogoutHandler logoutHandler;

    CredentialVersionFilter(UserRepository users, SecurityContextLogoutHandler logoutHandler) {
        this.users = users;
        this.logoutHandler = logoutHandler;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/assets/") || path.equals("/favicon.ico");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal user
                && users.findCredentialVersion(user.id()).orElse(-1L) != user.credentialVersion()) {
            logoutHandler.logout(request, response, authentication);
        }
        chain.doFilter(request, response);
    }
}

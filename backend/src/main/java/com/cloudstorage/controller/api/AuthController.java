package com.cloudstorage.controller.api;

import com.cloudstorage.dto.AuthRequests.LoginRequest;
import com.cloudstorage.dto.AuthRequests.RegisterRequest;
import com.cloudstorage.dto.UserResponse;
import com.cloudstorage.exception.ApiException;
import com.cloudstorage.model.UserAccount;
import com.cloudstorage.security.UserPrincipal;
import com.cloudstorage.service.AuthService;
import com.cloudstorage.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final AuthService authService;
    private final SessionService sessions;

    @Autowired
    public AuthController(AuthService authService, SessionService sessions) {
        this.authService = authService;
        this.sessions = sessions;
    }

    @GetMapping("/auth/csrf")
    public CsrfResponse csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            throw new IllegalStateException("CSRF token is unavailable");
        }
        return new CsrfResponse(token.getHeaderName(), token.getParameterName(), token.getToken());
    }

    @PostMapping("/auth/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        UserAccount user = authService.register(request.email(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    @PostMapping("/auth/login")
    public UserResponse login(@Valid @RequestBody LoginRequest request,
                              HttpServletRequest servletRequest,
                              HttpServletResponse servletResponse) {
        return sessions.login(request.email(), request.password(), servletRequest, servletResponse);
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response,
                                       Authentication authentication) {
        sessions.logout(request, response, authentication);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/me")
    public UserResponse me(Authentication authentication) {
        UserPrincipal principal = requireUser(authentication.getPrincipal());
        return authService.accountDetails(principal.id());
    }

    private UserPrincipal requireUser(Object principal) {
        if (principal instanceof UserPrincipal user) {
            return user;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_session", "The session is not valid.");
    }

    public record CsrfResponse(String headerName, String parameterName, String token) {
    }
}

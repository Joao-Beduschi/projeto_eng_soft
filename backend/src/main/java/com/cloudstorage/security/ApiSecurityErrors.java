package com.cloudstorage.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class ApiSecurityErrors {
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, exception) -> write(request, response, 401,
                "unauthorized", "Authentication is required.");
    }

    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, exception) -> write(request, response, 403,
                "forbidden", "The request is not allowed.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response,
                       int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"timestamp\":\"" + Instant.now()
                + "\",\"status\":" + status
                + ",\"code\":\"" + code
                + "\",\"message\":\"" + message
                + "\",\"path\":\"" + escapeJson(request.getRequestURI()) + "\"}");
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}

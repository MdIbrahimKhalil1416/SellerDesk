package com.sellerdesk.common.security;

import com.sellerdesk.common.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes security errors in the same {@link ApiError} format as GlobalExceptionHandler.
 *
 * <p>Why this class exists: Spring Security filters run <b>before</b> Spring MVC, so a
 * {@code @RestControllerAdvice} never sees "not logged in" errors raised there.
 * <ul>
 *   <li>{@link AuthenticationEntryPoint}: no valid login → 401</li>
 *   <li>{@link AccessDeniedHandler}: logged in but not allowed → 403</li>
 * </ul>
 *
 * <p>401 codes: {@code TOKEN_EXPIRED} (frontend should call /auth/refresh), {@code INVALID_TOKEN}
 * (bad, revoked or tampered token: log in again), {@code UNAUTHORIZED} (no token sent).
 * The reason is left in a request attribute by {@link JwtAuthenticationFilter}.
 */
@Component
@RequiredArgsConstructor
public class RestSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Object reason = request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE);
        ApiError body;
        if (JwtAuthenticationFilter.TOKEN_EXPIRED.equals(reason)) {
            body = ApiError.of(HttpStatus.UNAUTHORIZED, JwtAuthenticationFilter.TOKEN_EXPIRED,
                    "Access token has expired", request.getRequestURI());
        } else if (JwtAuthenticationFilter.INVALID_TOKEN.equals(reason)) {
            body = ApiError.of(HttpStatus.UNAUTHORIZED, JwtAuthenticationFilter.INVALID_TOKEN,
                    "Access token is invalid. Please log in again", request.getRequestURI());
        } else {
            body = ApiError.of(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "Authentication is required", request.getRequestURI());
        }
        write(response, body);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(response, ApiError.of(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You do not have permission to perform this action", request.getRequestURI()));
    }

    private void write(HttpServletResponse response, ApiError body) throws IOException {
        response.setStatus(body.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}

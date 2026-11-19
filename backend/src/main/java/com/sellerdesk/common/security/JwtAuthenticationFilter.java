package com.sellerdesk.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs once for every HTTP request, before any controller.
 *
 * <ol>
 *   <li>No {@code Authorization: Bearer ...} header → do nothing. The request continues as
 *       anonymous; Spring Security later answers 401 if the URL is not public.</li>
 *   <li>Token present → verify it ({@link JwtService}), load the user (cached) and check the user
 *       is still ACTIVE and the token's {@code ver} equals the user's current tokenVersion.</li>
 *   <li>All good → put the user into the {@link SecurityContext} (so {@code @PreAuthorize} works)
 *       and the shopId into {@link TenantContext}.</li>
 *   <li>Token bad → stay anonymous and remember why in a request attribute, so the 401 response
 *       can say {@code TOKEN_EXPIRED} or {@code INVALID_TOKEN}. We do not answer 401 here:
 *       public endpoints such as login must still work even if the browser sends an old token.</li>
 *   <li>Always, even after an exception → clear TenantContext.</li>
 * </ol>
 *
 * <p>Not a Spring {@code @Component} on purpose: Spring Boot registers every Filter bean as a
 * plain servlet filter too, so it would run twice. SecurityConfig creates it with {@code new}.
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Request attribute holding the 401 error code, read by {@link RestSecurityErrorHandler}. */
    public static final String AUTH_ERROR_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".AUTH_ERROR";
    public static final String TOKEN_EXPIRED = "TOKEN_EXPIRED";
    public static final String INVALID_TOKEN = "INVALID_TOKEN";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final AppUserDetailsService userDetailsService;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final WebAuthenticationDetailsSource detailsSource = new WebAuthenticationDetailsSource();

    public JwtAuthenticationFilter(JwtService jwtService, AppUserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String token = extractBearerToken(request);
            if (token != null) {
                authenticate(token, request);
            }
            chain.doFilter(request, response);
        } finally {
            // Threads are reused: never let one request's shopId leak into the next.
            TenantContext.clear();
        }
    }

    private void authenticate(String token, HttpServletRequest request) {
        try {
            AccessTokenClaims claims = jwtService.parseAccessToken(token);
            AppUserDetails user = userDetailsService.loadUserById(claims.userId());

            if (!user.isEnabled()) {
                throw new InvalidTokenException(InvalidTokenException.Reason.INVALID, "User is not active");
            }
            if (user.tokenVersion() != claims.tokenVersion()) {
                throw new InvalidTokenException(InvalidTokenException.Reason.INVALID, "Token revoked (old tokenVersion)");
            }
            if (!Objects.equals(user.shopId(), claims.shopId())) {
                throw new InvalidTokenException(InvalidTokenException.Reason.INVALID, "Token shopId does not match user");
            }

            // Authorities come from the signed token. They match the database, because every
            // permission/role change increments tokenVersion and so kills older tokens.
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    user, null, AppUserDetails.toAuthorities(claims.role(), claims.permissions()));
            authentication.setDetails(detailsSource.buildDetails(request));

            SecurityContext context = contextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            contextHolder.setContext(context);
            TenantContext.setShopId(claims.shopId());

        } catch (InvalidTokenException e) {
            reject(request, e.getReason() == InvalidTokenException.Reason.EXPIRED ? TOKEN_EXPIRED : INVALID_TOKEN,
                    e.getMessage());
        } catch (UsernameNotFoundException e) {
            reject(request, INVALID_TOKEN, "User of token not found");
        }
    }

    private void reject(HttpServletRequest request, String code, String reason) {
        // Never log the token itself.
        log.debug("JWT rejected for {} {}: {}", request.getMethod(), request.getRequestURI(), reason);
        contextHolder.clearContext();
        request.setAttribute(AUTH_ERROR_ATTRIBUTE, code);
    }

    private static String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length()).trim();
    }
}

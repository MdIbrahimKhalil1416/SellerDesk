package com.sellerdesk.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security rules for the whole API (docs/SECURITY.md).
 *
 * <p>One service serves both the API ({@code /api/**}) and the built React app (every other path),
 * so the rules are split by path:
 * <ul>
 *   <li>{@code /api/**}: needs a valid access token, except the public auth endpoints below.</li>
 *   <li>{@code /actuator/health}: public (uptime monitor). Every other actuator path is denied.</li>
 *   <li>Everything else (index.html, /assets/*, SPA routes like /dashboard): public. These are
 *       only the app's static files; all data comes from {@code /api/**}. New controllers must
 *       therefore live under {@code /api/}, or they would be public.</li>
 * </ul>
 *
 * <p>Two levels of checks:
 * <ol>
 *   <li><b>URL level</b> (here): the rules above. Missing/invalid token → 401.</li>
 *   <li><b>Method level</b> (on controllers): {@code @PreAuthorize("hasAuthority('ORDER_CREATE')")}.
 *       Logged in but missing that permission → 403.</li>
 * </ol>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity // turns on @PreAuthorize
@RequiredArgsConstructor
public class SecurityConfig {

    /** Cost factor 12 = 2^12 rounds, roughly 0.2-0.3 s per hash: slow for attackers, fine for one login. */
    static final int BCRYPT_STRENGTH = 12;

    /**
     * Swagger UI needs three paths: the HTML page, its static files, and the JSON API description
     * it loads ({@code /v3/api-docs}). In prod springdoc is disabled, so these paths return 404.
     */
    private static final String[] PUBLIC_PATHS = {
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            // Public auth endpoints (docs/API.md §1). Listed one by one on purpose: a new auth
            // endpoint such as logout must stay protected unless it is added here deliberately.
            "/api/v1/auth/register-shop",
            "/api/v1/auth/verify-email",
            "/api/v1/auth/verify-email/resend",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/password/forgot",
            "/api/v1/auth/password/reset",
            // Staff invitation accept page (docs/modules/STAFF.md §3.5): the token is the proof.
            "/api/v1/auth/invitations/preview",
            "/api/v1/auth/invitations/accept",
            // Courier webhooks (docs/modules/COURIER.md §5): no JWT, the courier's signature is the proof.
            "/api/v1/webhooks/courier/*",
    };

    private final RestSecurityErrorHandler securityErrorHandler;
    private final JwtService jwtService;
    private final AppUserDetailsService userDetailsService;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF protection is for cookie-based sessions. We use Bearer tokens (docs/SECURITY.md §6).
                .csrf(AbstractHttpConfigurer::disable)
                // No CORS: the browser loads the app and calls the API on the same origin (in dev the
                // Vite proxy makes it one origin too). Without CORS headers, other sites' JavaScript
                // cannot read our responses (docs/SECURITY.md §6).
                .cors(AbstractHttpConfigurer::disable)
                // No HTTP session: every request must carry its own token.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Turn off the browser login form / popup that Spring Security adds by default.
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(securityErrorHandler) // 401
                        .accessDeniedHandler(securityErrorHandler))     // 403
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers("/api/**").authenticated()
                        // Only overall status is shown (application-prod.yml); no other actuator
                        // endpoint is exposed, and denyAll keeps it closed if one is added later.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        // The React app: index.html, hashed assets and SPA routes (see SpaWebConfig).
                        .anyRequest().permitAll())
                // Our filter runs before Spring's (unused) username/password filter, i.e. early
                // enough that the authorization checks after it see the logged-in user.
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, userDetailsService),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** Hashes passwords and OTP codes. BCrypt adds a random salt, so equal inputs give different hashes. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }
}

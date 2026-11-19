package com.sellerdesk.common.security;

import com.sellerdesk.common.config.AppProperties;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Security rules for the whole API (docs/SECURITY.md).
 *
 * <p>Two levels of checks:
 * <ol>
 *   <li><b>URL level</b> (here): public paths are open, everything else needs a valid access token.
 *       Missing/invalid token → 401.</li>
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
            "/actuator/health",
            "/actuator/health/**",
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
    };

    private final AppProperties appProperties;
    private final RestSecurityErrorHandler securityErrorHandler;
    private final JwtService jwtService;
    private final AppUserDetailsService userDetailsService;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF protection is for cookie-based sessions. We use Bearer tokens (docs/SECURITY.md §6).
                .csrf(AbstractHttpConfigurer::disable)
                // Uses the corsConfigurationSource bean below.
                .cors(Customizer.withDefaults())
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
                        .anyRequest().authenticated())
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

    /** Allows the frontend (FRONTEND_URL) and only it to call the API from the browser. */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(appProperties.frontendUrl()));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true); // needed later for the refresh-token cookie
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

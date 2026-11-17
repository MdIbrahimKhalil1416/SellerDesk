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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * TEMPORARY security setup for the skeleton.
 *
 * <p>Only health and API docs are public; every other URL returns 401 because no login
 * mechanism exists yet. The JWT filter and the auth endpoints are added in the auth step.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity // enables @PreAuthorize on controller methods (used from the auth step on)
@RequiredArgsConstructor
public class SecurityConfig {

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
    };

    private final AppProperties appProperties;
    private final RestSecurityErrorHandler securityErrorHandler;

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
                        .authenticationEntryPoint(securityErrorHandler)
                        .accessDeniedHandler(securityErrorHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated());
        return http.build();
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

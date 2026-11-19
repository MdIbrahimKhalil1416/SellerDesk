package com.sellerdesk;

import com.sellerdesk.common.config.AppProperties;
import com.sellerdesk.common.security.JwtService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/** Shared test helpers for plain unit tests (no Spring context). */
public final class TestFixtures {

    public static final String JWT_SECRET = "unit-test-secret-unit-test-secret-0123456789";
    public static final String APP_URL = "http://localhost:5173";

    private TestFixtures() {
    }

    public static AppProperties properties(String jwtSecret) {
        return new AppProperties(APP_URL,
                new AppProperties.Jwt(jwtSecret, Duration.ofMinutes(15), Duration.ofDays(30)),
                new AppProperties.Mail("SellerDesk <no-reply@test.local>"),
                new AppProperties.RefreshCookie("Strict", false));
    }

    public static AppProperties properties() {
        return properties(JWT_SECRET);
    }

    public static JwtService jwtServiceAt(Instant now) {
        return new JwtService(properties(), Clock.fixed(now, ZoneOffset.UTC));
    }
}

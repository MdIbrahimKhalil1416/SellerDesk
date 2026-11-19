package com.sellerdesk.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * APP_URL is the only Origin the refresh endpoint accepts. A wrong value does not crash anything
 * by itself: every refresh just gets "403 INVALID_ORIGIN". So we reject bad values at startup.
 */
class AppPropertiesTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:5173", "https://sellerdesk-web.onrender.com", "https://app.example.com"})
    void acceptsAnOrigin(String appUrl) {
        assertThat(appUrlViolations(appUrl)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "${APP_URL}",                   // env variable not set: Spring keeps the placeholder text
            "http://localhost:5173/",       // trailing slash never matches the browser's Origin header
            "https://app.example.com/login", // a path is not part of an origin
            "localhost:5173",               // no scheme
    })
    void rejectsAnythingThatIsNotAnOrigin(String appUrl) {
        assertThat(appUrlViolations(appUrl)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"None", "strict", ""})
    void refreshCookieSameSiteMustBeStrictOrLax(String sameSite) {
        AppProperties.RefreshCookie cookie = new AppProperties.RefreshCookie(sameSite, true);
        assertThat(validator.validate(cookie)).isNotEmpty();
    }

    private Set<ConstraintViolation<AppProperties>> appUrlViolations(String appUrl) {
        AppProperties properties = new AppProperties(appUrl,
                new AppProperties.Jwt("x".repeat(32), Duration.ofMinutes(15), Duration.ofDays(30)),
                new AppProperties.Mail("SellerDesk <no-reply@sellerdesk.app>"),
                new AppProperties.RefreshCookie("Strict", false));
        return validator.validateProperty(properties, "appUrl");
    }
}

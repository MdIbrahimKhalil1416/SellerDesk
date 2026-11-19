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
 * FRONTEND_URL is the only allowed CORS origin. A wrong value does not crash anything by itself:
 * every browser request just gets "403 Invalid CORS request". So we reject bad values at startup.
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
    void acceptsAnOrigin(String frontendUrl) {
        assertThat(frontendUrlViolations(frontendUrl)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "${FRONTEND_URL}",              // env variable not set: Spring keeps the placeholder text
            "http://localhost:5173/",       // trailing slash never matches the browser's Origin header
            "https://app.example.com/login", // a path is not part of an origin
            "localhost:5173",               // no scheme
    })
    void rejectsAnythingThatIsNotAnOrigin(String frontendUrl) {
        assertThat(frontendUrlViolations(frontendUrl)).isNotEmpty();
    }

    private Set<ConstraintViolation<AppProperties>> frontendUrlViolations(String frontendUrl) {
        AppProperties properties = new AppProperties(frontendUrl,
                new AppProperties.Jwt("x".repeat(32), Duration.ofMinutes(15), Duration.ofDays(30)),
                new AppProperties.Mail("SellerDesk <no-reply@sellerdesk.app>"),
                new AppProperties.RefreshCookie("Strict", false));
        return validator.validateProperty(properties, "frontendUrl");
    }
}

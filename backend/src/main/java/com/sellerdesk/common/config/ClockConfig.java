package com.sellerdesk.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One shared {@link Clock} for "what time is it now".
 *
 * <p>Code that asks {@code Instant.now(clock)} instead of {@code Instant.now()} can be tested with
 * a fixed clock, e.g. to check that a token issued 16 minutes ago is rejected as expired.
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}

package com.sellerdesk.auth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Slows down password guessing: after 5 failed logins for an email, further logins for it are
 * refused for 15 minutes (counted from the last failure).
 *
 * <p>In memory (Caffeine), so the count is per app instance and resets on restart. Fine for one
 * Render instance; several instances would need a shared store (docs/SECURITY.md §1).
 */
@Component
public class LoginAttemptLimiter {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final Cache<String, AtomicInteger> failures = Caffeine.newBuilder()
            .expireAfterWrite(WINDOW)
            .maximumSize(100_000) // caps memory if someone sprays random emails
            .build();

    public boolean isBlocked(String email) {
        AtomicInteger count = failures.getIfPresent(email);
        return count != null && count.get() >= MAX_FAILURES;
    }

    public void recordFailure(String email) {
        // compute() re-writes the entry, which restarts its 15-minute expiry.
        failures.asMap().compute(email, (key, count) -> {
            AtomicInteger c = count == null ? new AtomicInteger() : count;
            c.incrementAndGet();
            return c;
        });
    }

    public void reset(String email) {
        failures.invalidate(email);
    }
}

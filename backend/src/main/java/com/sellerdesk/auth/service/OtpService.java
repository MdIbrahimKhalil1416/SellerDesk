package com.sellerdesk.auth.service;

import com.sellerdesk.auth.model.OtpPurpose;
import com.sellerdesk.auth.model.OtpToken;
import com.sellerdesk.auth.repository.OtpTokenRepository;
import com.sellerdesk.common.error.BusinessRuleException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Creates and checks 6-digit email codes (docs/SECURITY.md §1, docs/SCHEMA.md §4).
 *
 * <p>Rules: BCrypt hash only, valid 10 minutes, max 5 tries per code, one new code per 60 s,
 * max 5 codes per hour, single use, only the newest code counts.
 */
@Service
@RequiredArgsConstructor
public class OtpService {

    public static final Duration CODE_TTL = Duration.ofMinutes(10);
    public static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    static final Duration RATE_WINDOW = Duration.ofHours(1);
    static final int MAX_CODES_PER_WINDOW = 5;
    static final int MAX_ATTEMPTS = 5;

    static final String INVALID_OTP = "INVALID_OTP";
    static final String OTP_COOLDOWN = "OTP_COOLDOWN";
    static final String OTP_RATE_LIMITED = "OTP_RATE_LIMITED";

    /** Cryptographically strong; {@code java.util.Random} is predictable. */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpTokenRepository otpTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /**
     * Creates a new code and publishes {@link OtpIssuedEvent} so it gets emailed.
     *
     * @throws BusinessRuleException 429 {@code OTP_COOLDOWN} or {@code OTP_RATE_LIMITED}
     */
    public void issue(String email, OtpPurpose purpose, String recipientName) {
        Instant now = clock.instant();

        Optional<OtpToken> latest = otpTokenRepository.findFirstByEmailAndPurposeOrderByCreatedAtDesc(email, purpose);
        if (latest.isPresent()) {
            Instant nextAllowed = latest.get().getCreatedAt().plus(RESEND_COOLDOWN);
            if (nextAllowed.isAfter(now)) {
                long wait = Math.max(1, Duration.between(now, nextAllowed).toSeconds());
                throw new BusinessRuleException(HttpStatus.TOO_MANY_REQUESTS, OTP_COOLDOWN,
                        "Please wait " + wait + " seconds before requesting a new code");
            }
        }
        long sentRecently = otpTokenRepository.countByEmailAndPurposeAndCreatedAtAfter(
                email, purpose, now.minus(RATE_WINDOW));
        if (sentRecently >= MAX_CODES_PER_WINDOW) {
            throw new BusinessRuleException(HttpStatus.TOO_MANY_REQUESTS, OTP_RATE_LIMITED,
                    "Too many codes requested. Please try again later");
        }

        String code = "%06d".formatted(RANDOM.nextInt(1_000_000)); // 000000-999999, keeps leading zeros

        OtpToken token = new OtpToken();
        token.setEmail(email);
        token.setPurpose(purpose);
        token.setCodeHash(passwordEncoder.encode(code));
        token.setAttempts(0);
        token.setCreatedAt(now);
        token.setExpiresAt(now.plus(CODE_TTL));
        token.setPurgeAt(now.plus(RATE_WINDOW));
        otpTokenRepository.save(token);

        events.publishEvent(new OtpIssuedEvent(email, recipientName, purpose, code));
    }

    /**
     * Checks the code and marks it used. Every failure gives the same {@code 400 INVALID_OTP},
     * so a caller cannot tell "wrong code" from "expired" or "no code for this email".
     */
    public void verify(String email, OtpPurpose purpose, String code) {
        Instant now = clock.instant();
        OtpToken token = otpTokenRepository.findFirstByEmailAndPurposeOrderByCreatedAtDesc(email, purpose)
                .orElseThrow(OtpService::invalidOtp);

        if (token.getConsumedAt() != null || !token.getExpiresAt().isAfter(now)
                || token.getAttempts() >= MAX_ATTEMPTS) {
            throw invalidOtp();
        }
        // Count this try first (atomically). Only then compare, so parallel guesses can't exceed 5.
        if (otpTokenRepository.reserveAttempt(token.getId(), MAX_ATTEMPTS) == 0) {
            throw invalidOtp();
        }
        // BCrypt's matches() compares in constant time: response time reveals nothing about the code.
        if (code == null || !passwordEncoder.matches(code, token.getCodeHash())) {
            throw invalidOtp();
        }
        if (otpTokenRepository.markConsumed(token.getId(), now) == 0) {
            throw invalidOtp(); // another request used it a moment earlier
        }
    }

    private static BusinessRuleException invalidOtp() {
        return new BusinessRuleException(HttpStatus.BAD_REQUEST, INVALID_OTP, "The code is invalid or has expired");
    }
}

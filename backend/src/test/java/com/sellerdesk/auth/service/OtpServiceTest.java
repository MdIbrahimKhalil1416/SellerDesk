package com.sellerdesk.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerdesk.auth.model.OtpPurpose;
import com.sellerdesk.auth.model.OtpToken;
import com.sellerdesk.auth.repository.OtpTokenRepository;
import com.sellerdesk.common.error.BusinessRuleException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

class OtpServiceTest {

    static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    static final String EMAIL = "rahim@example.com";
    static final OtpPurpose PURPOSE = OtpPurpose.EMAIL_VERIFICATION;

    private final OtpTokenRepository repository = mock(OtpTokenRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final OtpService service = new OtpService(repository, encoder, events, Clock.fixed(NOW, ZoneOffset.UTC));

    // ---- issue --------------------------------------------------------------------------

    @Test
    void issueStoresOnlyHashAndPublishesPlainCodeForEmail() {
        when(repository.findFirstByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, PURPOSE)).thenReturn(Optional.empty());
        when(encoder.encode(anyString())).thenReturn("HASHED");

        service.issue(EMAIL, PURPOSE, "Rahim");

        ArgumentCaptor<OtpToken> saved = ArgumentCaptor.forClass(OtpToken.class);
        verify(repository).save(saved.capture());
        ArgumentCaptor<OtpIssuedEvent> event = ArgumentCaptor.forClass(OtpIssuedEvent.class);
        verify(events).publishEvent(event.capture());

        String code = event.getValue().code();
        assertThat(code).matches("\\d{6}");
        verify(encoder).encode(code);

        OtpToken token = saved.getValue();
        assertThat(token.getCodeHash()).isEqualTo("HASHED").isNotEqualTo(code);
        assertThat(token.getEmail()).isEqualTo(EMAIL);
        assertThat(token.getPurpose()).isEqualTo(PURPOSE);
        assertThat(token.getAttempts()).isZero();
        assertThat(token.getCreatedAt()).isEqualTo(NOW);
        assertThat(token.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(token.getPurgeAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(event.getValue().toString()).doesNotContain(code);
    }

    @Test
    void issueWithinCooldownIsRejected() {
        when(repository.findFirstByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, PURPOSE))
                .thenReturn(Optional.of(token(NOW.minusSeconds(20), 0, null)));

        assertThatThrownBy(() -> service.issue(EMAIL, PURPOSE, "Rahim"))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getCode()).isEqualTo("OTP_COOLDOWN");
                    assertThat(e.getMessage()).contains("40 seconds");
                });
        verify(repository, never()).save(any());
    }

    @Test
    void issueAfterCooldownIsAllowed() {
        when(repository.findFirstByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, PURPOSE))
                .thenReturn(Optional.of(token(NOW.minusSeconds(61), 0, null)));
        when(repository.countByEmailAndPurposeAndCreatedAtAfter(eq(EMAIL), eq(PURPOSE), any())).thenReturn(1L);

        service.issue(EMAIL, PURPOSE, "Rahim");

        verify(repository).save(any());
    }

    @Test
    void issueAfterFiveCodesInAnHourIsRejected() {
        when(repository.findFirstByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, PURPOSE))
                .thenReturn(Optional.of(token(NOW.minusSeconds(300), 0, null)));
        when(repository.countByEmailAndPurposeAndCreatedAtAfter(EMAIL, PURPOSE, NOW.minus(Duration.ofHours(1))))
                .thenReturn(5L);

        assertThatThrownBy(() -> service.issue(EMAIL, PURPOSE, "Rahim"))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OTP_RATE_LIMITED"));
        verify(repository, never()).save(any());
    }

    // ---- verify -------------------------------------------------------------------------

    @Test
    void correctCodeIsAcceptedAndConsumed() {
        givenLatest(token(NOW.minusSeconds(60), 0, null));
        when(repository.reserveAttempt("otp1", 5)).thenReturn(1L);
        when(encoder.matches("123456", "HASH")).thenReturn(true);
        when(repository.markConsumed("otp1", NOW)).thenReturn(1L);

        service.verify(EMAIL, PURPOSE, "123456");

        verify(repository).markConsumed("otp1", NOW);
    }

    @Test
    void wrongCodeIsRejectedAndAttemptCounted() {
        givenLatest(token(NOW.minusSeconds(60), 0, null));
        when(repository.reserveAttempt("otp1", 5)).thenReturn(1L);
        when(encoder.matches("000000", "HASH")).thenReturn(false);

        assertInvalidOtp(() -> service.verify(EMAIL, PURPOSE, "000000"));
        verify(repository).reserveAttempt("otp1", 5);
        verify(repository, never()).markConsumed(any(), any());
    }

    @Test
    void codeWithFiveAttemptsUsedIsDeadEvenIfCorrect() {
        givenLatest(token(NOW.minusSeconds(60), 5, null));

        assertInvalidOtp(() -> service.verify(EMAIL, PURPOSE, "123456"));
        verify(encoder, never()).matches(any(), any());
    }

    @Test
    void attemptReservationLostToParallelRequestIsRejected() {
        givenLatest(token(NOW.minusSeconds(60), 4, null));
        when(repository.reserveAttempt("otp1", 5)).thenReturn(0L); // another request took the last try

        assertInvalidOtp(() -> service.verify(EMAIL, PURPOSE, "123456"));
        verify(encoder, never()).matches(any(), any());
    }

    @Test
    void expiredCodeIsRejected() {
        givenLatest(token(NOW.minus(Duration.ofMinutes(11)), 0, null));

        assertInvalidOtp(() -> service.verify(EMAIL, PURPOSE, "123456"));
    }

    @Test
    void alreadyUsedCodeIsRejected() {
        givenLatest(token(NOW.minusSeconds(60), 1, NOW.minusSeconds(30)));

        assertInvalidOtp(() -> service.verify(EMAIL, PURPOSE, "123456"));
    }

    @Test
    void noCodeForEmailIsRejected() {
        when(repository.findFirstByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, PURPOSE)).thenReturn(Optional.empty());

        assertInvalidOtp(() -> service.verify(EMAIL, PURPOSE, "123456"));
    }

    @Test
    void codeUsedByParallelRequestIsRejected() {
        givenLatest(token(NOW.minusSeconds(60), 0, null));
        when(repository.reserveAttempt("otp1", 5)).thenReturn(1L);
        when(encoder.matches("123456", "HASH")).thenReturn(true);
        when(repository.markConsumed("otp1", NOW)).thenReturn(0L);

        assertInvalidOtp(() -> service.verify(EMAIL, PURPOSE, "123456"));
    }

    // ---- helpers ------------------------------------------------------------------------

    private void givenLatest(OtpToken token) {
        when(repository.findFirstByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, PURPOSE)).thenReturn(Optional.of(token));
    }

    private static OtpToken token(Instant createdAt, int attempts, Instant consumedAt) {
        OtpToken t = new OtpToken();
        t.setId("otp1");
        t.setEmail(EMAIL);
        t.setPurpose(PURPOSE);
        t.setCodeHash("HASH");
        t.setAttempts(attempts);
        t.setCreatedAt(createdAt);
        t.setExpiresAt(createdAt.plus(OtpService.CODE_TTL));
        t.setPurgeAt(createdAt.plus(Duration.ofHours(1)));
        t.setConsumedAt(consumedAt);
        return t;
    }

    private static void assertInvalidOtp(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessRuleException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getCode()).isEqualTo("INVALID_OTP");
        });
    }
}

package com.sellerdesk.courier.provider.mock;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerdesk.courier.provider.InvalidSignatureException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class WebhookSignerTest {

    private static final String SECRET = "a-secret-that-is-long-enough-0123456789";
    private static final Instant NOW = Instant.parse("2026-11-28T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final byte[] BODY = "{\"status\":\"picked_up\"}".getBytes(StandardCharsets.UTF_8);

    private static HttpHeaders headers(String timestamp, String signature) {
        HttpHeaders h = new HttpHeaders();
        if (timestamp != null) {
            h.add(WebhookSigner.TIMESTAMP_HEADER, timestamp);
        }
        if (signature != null) {
            h.add(WebhookSigner.SIGNATURE_HEADER, signature);
        }
        return h;
    }

    private static HttpHeaders signedAt(long ts, String secret, byte[] body) {
        return headers(Long.toString(ts), WebhookSigner.sign(secret, ts, body));
    }

    @Test
    void validSignatureIsAccepted() {
        long ts = NOW.getEpochSecond();
        assertThatCode(() -> WebhookSigner.verify(SECRET, signedAt(ts, SECRET, BODY), BODY, CLOCK)).doesNotThrowAnyException();
        // Within the window on both sides.
        assertThatCode(() -> WebhookSigner.verify(SECRET, signedAt(ts - 299, SECRET, BODY), BODY, CLOCK)).doesNotThrowAnyException();
        assertThatCode(() -> WebhookSigner.verify(SECRET, signedAt(ts + 299, SECRET, BODY), BODY, CLOCK)).doesNotThrowAnyException();
    }

    @Test
    void bodyChangedByOneByteIsRejected() {
        byte[] changed = "{\"status\":\"delivered\"}".getBytes(StandardCharsets.UTF_8);
        assertRejected(signedAt(NOW.getEpochSecond(), SECRET, BODY), changed);
    }

    @Test
    void wrongSecretIsRejected() {
        assertRejected(signedAt(NOW.getEpochSecond(), "another-secret-that-is-long-enough-012345", BODY), BODY);
    }

    @Test
    void oldAndFutureTimestampsAreRejected() {
        assertRejected(signedAt(NOW.getEpochSecond() - 360, SECRET, BODY), BODY);
        assertRejected(signedAt(NOW.getEpochSecond() + 360, SECRET, BODY), BODY);
    }

    @Test
    void timestampCannotBeChangedWithoutBreakingTheSignature() {
        String signature = WebhookSigner.sign(SECRET, NOW.getEpochSecond() - 1000, BODY);
        assertRejected(headers(Long.toString(NOW.getEpochSecond()), signature), BODY);
    }

    @Test
    void missingOrMalformedHeadersAreRejected() {
        String ts = Long.toString(NOW.getEpochSecond());
        assertRejected(headers(null, WebhookSigner.sign(SECRET, NOW.getEpochSecond(), BODY)), BODY);
        assertRejected(headers(ts, null), BODY);
        assertRejected(headers("yesterday", WebhookSigner.sign(SECRET, NOW.getEpochSecond(), BODY)), BODY);
        assertRejected(headers(ts, "sha1=abcdef"), BODY);
        assertRejected(headers(ts, "sha256=not-hex"), BODY);
    }

    @Test
    void noSecretConfiguredRejectsEverything() {
        HttpHeaders h = signedAt(NOW.getEpochSecond(), SECRET, BODY);
        assertThatThrownBy(() -> WebhookSigner.verify("", h, BODY, CLOCK)).isInstanceOf(InvalidSignatureException.class);
        assertThatThrownBy(() -> WebhookSigner.verify(null, h, BODY, CLOCK)).isInstanceOf(InvalidSignatureException.class);
    }

    private static void assertRejected(HttpHeaders headers, byte[] body) {
        assertThatThrownBy(() -> WebhookSigner.verify(SECRET, headers, body, CLOCK))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessage("Invalid webhook signature"); // the same message for every cause
    }
}

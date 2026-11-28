package com.sellerdesk.courier.provider.mock;

import com.sellerdesk.courier.provider.InvalidSignatureException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpHeaders;

/**
 * The mock courier's webhook signature (docs/modules/COURIER.md §5):
 *
 * <pre>
 * X-SD-Timestamp: 1764320000                       (Unix seconds)
 * X-SD-Signature: sha256=&lt;hex(HMAC_SHA256(secret, timestamp + "." + rawBody))&gt;
 * </pre>
 *
 * <ul>
 *   <li><b>HMAC</b>: only someone who knows the secret can compute it; changing one byte of the
 *       body gives a completely different value.</li>
 *   <li><b>Timestamp inside the signature</b>: a captured request cannot be replayed later, because
 *       a timestamp more than {@link #MAX_SKEW} from our clock is refused, and changing it breaks
 *       the signature.</li>
 *   <li><b>Constant-time compare</b> ({@link MessageDigest#isEqual}): a normal {@code equals} stops at
 *       the first different character, so response times would leak how much of a guess was right.</li>
 * </ul>
 * Real couriers use their own scheme; their adapters do their own check.
 */
public final class WebhookSigner {

    public static final String TIMESTAMP_HEADER = "X-SD-Timestamp";
    public static final String SIGNATURE_HEADER = "X-SD-Signature";
    public static final Duration MAX_SKEW = Duration.ofMinutes(5);
    private static final String PREFIX = "sha256=";

    private WebhookSigner() {
    }

    /** The {@code X-SD-Signature} value for this timestamp and body. */
    public static String sign(String secret, long timestampSeconds, byte[] body) {
        return PREFIX + HexFormat.of().formatHex(hmac(secret, timestampSeconds, body));
    }

    /**
     * @throws InvalidSignatureException for every problem (no secret, missing / malformed headers,
     *                                   old or future timestamp, wrong signature)
     */
    public static void verify(String secret, HttpHeaders headers, byte[] body, Clock clock) {
        if (secret == null || secret.isEmpty()) {
            throw new InvalidSignatureException("no webhook secret configured");
        }
        String timestampText = headers.getFirst(TIMESTAMP_HEADER);
        String signature = headers.getFirst(SIGNATURE_HEADER);
        if (timestampText == null || signature == null) {
            throw new InvalidSignatureException("missing signature headers");
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampText.trim());
        } catch (NumberFormatException e) {
            throw new InvalidSignatureException("timestamp is not a number");
        }
        long skew = Math.abs(Instant.now(clock).getEpochSecond() - timestamp);
        if (skew > MAX_SKEW.toSeconds()) {
            throw new InvalidSignatureException("timestamp outside the allowed window (" + skew + " s)");
        }
        String trimmed = signature.trim();
        if (!trimmed.startsWith(PREFIX)) {
            throw new InvalidSignatureException("signature format");
        }
        byte[] given;
        try {
            given = HexFormat.of().parseHex(trimmed.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new InvalidSignatureException("signature is not hex");
        }
        if (!MessageDigest.isEqual(given, hmac(secret, timestamp, body))) {
            throw new InvalidSignatureException("signature mismatch");
        }
    }

    private static byte[] hmac(String secret, long timestampSeconds, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestampSeconds + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }
}

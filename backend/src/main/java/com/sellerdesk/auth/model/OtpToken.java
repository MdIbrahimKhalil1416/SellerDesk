package com.sellerdesk.auth.model;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One emailed 6-digit code (docs/SCHEMA.md §4). Only the BCrypt hash is stored.
 *
 * <p>Two dates on purpose: {@code expiresAt} (10 min) decides if the code still works and is
 * checked by the service; {@code purgeAt} (1 h) is when MongoDB's TTL job deletes the document.
 * Keeping it for an hour lets us count "codes sent in the last hour" for rate limiting.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = "codeHash")
@Document("otp_tokens")
@CompoundIndex(name = "email_purpose_createdAt", def = "{'email': 1, 'purpose': 1, 'createdAt': -1}")
public class OtpToken {

    @Id
    private String id;

    private String email;

    private OtpPurpose purpose;

    private String codeHash;

    /** Verify tries so far, including the successful one. */
    private int attempts;

    private Instant expiresAt;

    /**
     * TTL index: {@code expireAfter = "0s"} means "delete when this date is in the past".
     * MongoDB's TTL job runs about once a minute, so deletion can lag. Never rely on it for security.
     */
    @Indexed(expireAfter = "0s")
    private Instant purgeAt;

    private Instant consumedAt;

    /** Set by the service from the injected Clock (not auditing), so tests can control time. */
    private Instant createdAt;
}

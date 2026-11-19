package com.sellerdesk.auth.repository;

import com.sellerdesk.auth.model.OtpPurpose;
import com.sellerdesk.auth.model.OtpToken;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.data.repository.Repository;

/**
 * OTP codes are not tenant data: they belong to an email address, before any shop is known.
 */
public interface OtpTokenRepository extends Repository<OtpToken, String> {

    OtpToken save(OtpToken token);

    /** The newest code. Only this one can be used; older ones are ignored. */
    Optional<OtpToken> findFirstByEmailAndPurposeOrderByCreatedAtDesc(String email, OtpPurpose purpose);

    /** How many codes were sent since {@code after}, for the "max per hour" limit. */
    long countByEmailAndPurposeAndCreatedAtAfter(String email, OtpPurpose purpose, Instant after);

    /**
     * Uses up one attempt <b>atomically</b>, only if attempts are left and the code is unused.
     * Returns 1 if an attempt was reserved, 0 if not.
     *
     * <p>Why not "read, attempts++, save"? Two parallel requests could both read attempts = 4
     * and both get a try. A single conditional {@code $inc} on the server cannot be raced.
     */
    @Query("{ '_id': ?0, 'attempts': { '$lt': ?1 }, 'consumedAt': null }")
    @Update("{ '$inc': { 'attempts': 1 } }")
    long reserveAttempt(String id, int maxAttempts);

    /** Marks the code used. Returns 0 if another request used it first. */
    @Query("{ '_id': ?0, 'consumedAt': null }")
    @Update("{ '$set': { 'consumedAt': ?1 } }")
    long markConsumed(String id, Instant consumedAt);
}

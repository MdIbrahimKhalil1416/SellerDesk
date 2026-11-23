package com.sellerdesk.staff.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Invite link tokens (docs/modules/STAFF.md §1).
 *
 * <ul>
 *   <li>Token: 32 random bytes from {@link SecureRandom}, base64url (43 characters). 256 random
 *       bits cannot be guessed, so no attempt limit is needed.</li>
 *   <li>Stored: only the SHA-256 hash. SHA-256 and not BCrypt, because the accept page must
 *       <i>find</i> the invitation by its hash; BCrypt hashes are salted and cannot be searched.</li>
 * </ul>
 */
public final class InvitationTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private InvitationTokens() {
    }

    public static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 as lowercase hex. */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }
}

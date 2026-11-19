package com.sellerdesk.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerdesk.TestFixtures;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Plain unit test: no Spring context, no database. */
class JwtServiceTest {

    static final String SECRET = "unit-test-secret-unit-test-secret-0123456789";
    static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");

    private final JwtService jwtService = serviceAt(NOW, SECRET);

    static JwtService serviceAt(Instant instant, String secret) {
        return new JwtService(TestFixtures.properties(secret), Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    void accessTokenRoundTripKeepsAllClaims() {
        Set<Permission> perms = EnumSet.of(Permission.ORDER_VIEW, Permission.ORDER_CREATE);
        String token = jwtService.generateAccessToken("user1", "shopA", Role.MODERATOR, perms, 3);

        AccessTokenClaims claims = jwtService.parseAccessToken(token);

        assertThat(claims.userId()).isEqualTo("user1");
        assertThat(claims.shopId()).isEqualTo("shopA");
        assertThat(claims.role()).isEqualTo(Role.MODERATOR);
        assertThat(claims.permissions()).containsExactlyInAnyOrderElementsOf(perms);
        assertThat(claims.tokenVersion()).isEqualTo(3);
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void platformAdminTokenHasNoShopId() {
        String token = jwtService.generateAccessToken("admin", null, Role.PLATFORM_ADMIN, Set.of(), 0);

        AccessTokenClaims claims = jwtService.parseAccessToken(token);

        assertThat(claims.shopId()).isNull();
        assertThat(claims.permissions()).isEmpty();
    }

    @Test
    void generateFromUserDetailsUsesItsFields() {
        AppUserDetails user = new AppUserDetails("u9", "shopZ", "a@b.com", "hash", Role.PACKER,
                Role.PACKER.defaultPermissions(), true, 7);

        AccessTokenClaims claims = jwtService.parseAccessToken(jwtService.generateAccessToken(user));

        assertThat(claims.userId()).isEqualTo("u9");
        assertThat(claims.shopId()).isEqualTo("shopZ");
        assertThat(claims.permissions()).isEqualTo(Role.PACKER.defaultPermissions());
        assertThat(claims.tokenVersion()).isEqualTo(7);
    }

    @Test
    void refreshTokenRoundTrip() {
        String token = jwtService.generateRefreshToken("user1", 5);

        RefreshTokenClaims claims = jwtService.parseRefreshToken(token);

        assertThat(claims.userId()).isEqualTo("user1");
        assertThat(claims.tokenVersion()).isEqualTo(5);
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
    }

    @Test
    void expiredAccessTokenIsRejectedAsExpired() {
        String token = jwtService.generateAccessToken("user1", "shopA", Role.SHOP_OWNER, Set.of(), 0);
        JwtService sixteenMinutesLater = serviceAt(NOW.plus(Duration.ofMinutes(16)), SECRET);

        assertReason(() -> sixteenMinutesLater.parseAccessToken(token), InvalidTokenException.Reason.EXPIRED);
    }

    @Test
    void accessTokenIsStillValidJustBeforeExpiry() {
        String token = jwtService.generateAccessToken("user1", "shopA", Role.SHOP_OWNER, Set.of(), 0);
        JwtService fourteenMinutesLater = serviceAt(NOW.plus(Duration.ofMinutes(14)), SECRET);

        assertThat(fourteenMinutesLater.parseAccessToken(token).userId()).isEqualTo("user1");
    }

    @Test
    void refreshTokenCannotBeUsedAsAccessToken() {
        String refresh = jwtService.generateRefreshToken("user1", 0);

        assertReason(() -> jwtService.parseAccessToken(refresh), InvalidTokenException.Reason.INVALID);
    }

    @Test
    void accessTokenCannotBeUsedAsRefreshToken() {
        String access = jwtService.generateAccessToken("user1", "shopA", Role.SHOP_OWNER, Set.of(), 0);

        assertReason(() -> jwtService.parseRefreshToken(access), InvalidTokenException.Reason.INVALID);
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        JwtService attacker = serviceAt(NOW, "attacker-secret-attacker-secret-0123456789");
        String forged = attacker.generateAccessToken("user1", "shopA", Role.SHOP_OWNER,
                EnumSet.allOf(Permission.class), 0);

        assertReason(() -> jwtService.parseAccessToken(forged), InvalidTokenException.Reason.INVALID);
    }

    @Test
    void tamperedPayloadIsRejected() {
        // Attacker changes the shopId inside a real token but cannot recompute the signature.
        String token = jwtService.generateAccessToken("user1", "shopA", Role.MODERATOR, Set.of(), 0);
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String changed = payload.replace("shopA", "shopB");
        assertThat(changed).isNotEqualTo(payload);
        String tampered = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(changed.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        assertReason(() -> jwtService.parseAccessToken(tampered), InvalidTokenException.Reason.INVALID);
    }

    @Test
    void unsignedTokenIsRejected() {
        // "alg": "none" attack: a token with no signature at all.
        String unsigned = Jwts.builder()
                .issuer(JwtService.ISSUER)
                .subject("user1")
                .claim("shopId", "shopA")
                .claim("role", "SHOP_OWNER")
                .claim("perms", java.util.List.of("ORDER_VIEW"))
                .claim("ver", 0)
                .claim("typ", "access")
                .expiration(Date.from(NOW.plus(Duration.ofMinutes(15))))
                .compact();

        assertReason(() -> jwtService.parseAccessToken(unsigned), InvalidTokenException.Reason.INVALID);
    }

    @Test
    void garbageAndEmptyTokensAreRejected() {
        assertReason(() -> jwtService.parseAccessToken("not-a-jwt"), InvalidTokenException.Reason.INVALID);
        assertReason(() -> jwtService.parseAccessToken(""), InvalidTokenException.Reason.INVALID);
        assertReason(() -> jwtService.parseAccessToken(null), InvalidTokenException.Reason.INVALID);
    }

    @Test
    void tokenWithUnknownPermissionIsRejected() {
        String token = Jwts.builder()
                .issuer(JwtService.ISSUER)
                .subject("user1")
                .claim("role", "SHOP_OWNER")
                .claim("perms", java.util.List.of("DELETE_EVERYTHING"))
                .claim("ver", 0)
                .claim("typ", "access")
                .expiration(Date.from(NOW.plus(Duration.ofMinutes(15))))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertReason(() -> jwtService.parseAccessToken(token), InvalidTokenException.Reason.INVALID);
    }

    private static void assertReason(Runnable call, InvalidTokenException.Reason reason) {
        assertThatThrownBy(call::run)
                .isInstanceOf(InvalidTokenException.class)
                .extracting(e -> ((InvalidTokenException) e).getReason())
                .isEqualTo(reason);
    }
}

package com.sellerdesk.common.security;

import com.sellerdesk.common.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Creates and checks our JWTs (docs/SECURITY.md §2).
 *
 * <p>A JWT is three base64 parts: header, claims (JSON data), and a signature. The signature is
 * an HMAC-SHA256 of the first two parts made with {@code JWT_SECRET}. Anyone can <b>read</b> the
 * claims, but nobody without the secret can <b>change</b> them: the signature would no longer match.
 * So never put secrets in claims, and always verify before trusting them.
 *
 * <p>Two token types, told apart by the {@code typ} claim. A refresh token is rejected where an
 * access token is expected and vice versa.
 */
@Service
public class JwtService {

    static final String ISSUER = "sellerdesk";
    static final String TYPE_ACCESS = "access";
    static final String TYPE_REFRESH = "refresh";

    static final String CLAIM_SHOP_ID = "shopId";
    static final String CLAIM_ROLE = "role";
    static final String CLAIM_PERMISSIONS = "perms";
    static final String CLAIM_VERSION = "ver";
    static final String CLAIM_TYPE = "typ";

    private final SecretKey key;
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;
    private final Clock clock;

    public JwtService(AppProperties properties, Clock clock) {
        // AppProperties already guarantees >= 32 characters, i.e. >= 256 bits as HS256 requires.
        this.key = Keys.hmacShaKeyFor(properties.jwt().secret().getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtl = properties.jwt().accessTokenTtl();
        this.refreshTokenTtl = properties.jwt().refreshTokenTtl();
        this.clock = clock;
    }

    // ---- Create -------------------------------------------------------------------------

    public String generateAccessToken(AppUserDetails user) {
        return generateAccessToken(user.userId(), user.shopId(), user.role(), user.permissions(),
                user.tokenVersion());
    }

    public String generateAccessToken(String userId, String shopId, Role role,
                                      Set<Permission> permissions, int tokenVersion) {
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(userId)
                .claim(CLAIM_SHOP_ID, shopId) // jjwt leaves the claim out when null (PLATFORM_ADMIN)
                .claim(CLAIM_ROLE, role.name())
                .claim(CLAIM_PERMISSIONS, permissions.stream().map(Enum::name).sorted().toList())
                .claim(CLAIM_VERSION, tokenVersion)
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public String generateRefreshToken(String userId, int tokenVersion) {
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(userId)
                .claim(CLAIM_VERSION, tokenVersion)
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(refreshTokenTtl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** Access token lifetime in seconds, for the {@code expiresIn} field of login responses. */
    public long accessTokenTtlSeconds() {
        return accessTokenTtl.toSeconds();
    }

    // ---- Verify -------------------------------------------------------------------------

    /**
     * Verifies signature, issuer, expiry and type, then reads the claims.
     *
     * @throws InvalidTokenException with reason EXPIRED or INVALID
     */
    public AccessTokenClaims parseAccessToken(String token) {
        Claims claims = verify(token, TYPE_ACCESS);
        try {
            return new AccessTokenClaims(
                    requireSubject(claims),
                    claims.get(CLAIM_SHOP_ID, String.class),
                    Role.valueOf(claims.get(CLAIM_ROLE, String.class)),
                    readPermissions(claims),
                    requireVersion(claims),
                    claims.getExpiration().toInstant());
        } catch (RuntimeException e) {
            // Missing claim, unknown role/permission name, wrong JSON type...
            throw new InvalidTokenException(InvalidTokenException.Reason.INVALID, "Malformed access token claims", e);
        }
    }

    /** @throws InvalidTokenException with reason EXPIRED or INVALID */
    public RefreshTokenClaims parseRefreshToken(String token) {
        Claims claims = verify(token, TYPE_REFRESH);
        try {
            return new RefreshTokenClaims(requireSubject(claims), requireVersion(claims),
                    claims.getExpiration().toInstant());
        } catch (RuntimeException e) {
            throw new InvalidTokenException(InvalidTokenException.Reason.INVALID, "Malformed refresh token claims", e);
        }
    }

    private Claims verify(String token, String expectedType) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException(InvalidTokenException.Reason.INVALID, "Token is empty");
        }
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(key)          // wrong signature → exception
                    .requireIssuer(ISSUER)    // token from another system → exception
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token) // also rejects unsigned ("alg: none") tokens
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new InvalidTokenException(InvalidTokenException.Reason.EXPIRED, "Token expired", e);
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException(InvalidTokenException.Reason.INVALID, "Token rejected: " + e.getClass().getSimpleName(), e);
        }
        if (!expectedType.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new InvalidTokenException(InvalidTokenException.Reason.INVALID, "Wrong token type, expected " + expectedType);
        }
        return claims;
    }

    private static String requireSubject(Claims claims) {
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Missing subject");
        }
        return subject;
    }

    private static int requireVersion(Claims claims) {
        Integer version = claims.get(CLAIM_VERSION, Integer.class);
        if (version == null) {
            throw new IllegalArgumentException("Missing ver claim");
        }
        return version;
    }

    private static Set<Permission> readPermissions(Claims claims) {
        List<?> raw = claims.get(CLAIM_PERMISSIONS, List.class);
        if (raw == null) {
            throw new IllegalArgumentException("Missing perms claim");
        }
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        for (Object name : raw) {
            permissions.add(Permission.valueOf((String) name));
        }
        return permissions;
    }
}

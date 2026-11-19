package com.sellerdesk.auth.service;

import com.sellerdesk.auth.dto.LoginRequest;
import com.sellerdesk.auth.dto.MeResponse;
import com.sellerdesk.auth.dto.OtpSentResponse;
import com.sellerdesk.auth.dto.RegisterShopRequest;
import com.sellerdesk.auth.dto.RegisterShopResponse;
import com.sellerdesk.auth.dto.ResetPasswordRequest;
import com.sellerdesk.auth.dto.UserResponse;
import com.sellerdesk.auth.dto.VerifyEmailRequest;
import com.sellerdesk.auth.model.OtpPurpose;
import com.sellerdesk.auth.model.User;
import com.sellerdesk.auth.model.UserStatus;
import com.sellerdesk.auth.repository.UserRepository;
import com.sellerdesk.common.config.AppProperties;
import com.sellerdesk.common.error.BusinessRuleException;
import com.sellerdesk.common.error.FieldValidationException;
import com.sellerdesk.common.error.ResourceNotFoundException;
import com.sellerdesk.common.security.AppUserDetailsService;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.common.security.InvalidTokenException;
import com.sellerdesk.common.security.JwtService;
import com.sellerdesk.common.security.RefreshTokenClaims;
import com.sellerdesk.common.security.Role;
import com.sellerdesk.common.util.Normalize;
import com.sellerdesk.shop.dto.ShopSummary;
import com.sellerdesk.shop.service.ShopService;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Registration, email verification, login, refresh, logout, password reset and "me"
 * (docs/SECURITY.md §1–2, docs/API.md §1).
 */
@Slf4j
@Service
public class AuthService {

    static final String EMAIL_ALREADY_REGISTERED = "EMAIL_ALREADY_REGISTERED";
    static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    static final String EMAIL_NOT_VERIFIED = "EMAIL_NOT_VERIFIED";
    static final String LOGIN_RATE_LIMITED = "LOGIN_RATE_LIMITED";
    static final String INVALID_REFRESH_TOKEN = "INVALID_REFRESH_TOKEN";
    static final String INVALID_ORIGIN = "INVALID_ORIGIN";

    static final String RESEND_MESSAGE = "If this email is waiting for verification, a new code has been sent.";
    static final String FORGOT_MESSAGE = "If an account exists for this email, a password reset code has been sent.";

    /** Tokens + user, for the controller to turn into a JSON body and a cookie. */
    public record AuthResult(String accessToken, String refreshToken, long expiresIn, UserResponse user) {
    }

    private final UserRepository userRepository;
    private final ShopService shopService;
    private final OtpService otpService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AppUserDetailsService userDetailsService;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final TransactionOperations transactions;
    private final ApplicationEventPublisher events;
    private final AppProperties appProperties;
    private final CurrentUser currentUser;
    private final Clock clock;

    /**
     * A real BCrypt hash of a random value. Checked when the email is unknown, so "unknown email"
     * takes as long as "wrong password" and timing does not reveal which emails exist.
     */
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository, ShopService shopService, OtpService otpService,
                       JwtService jwtService, PasswordEncoder passwordEncoder,
                       AppUserDetailsService userDetailsService, LoginAttemptLimiter loginAttemptLimiter,
                       TransactionOperations transactions, ApplicationEventPublisher events,
                       AppProperties appProperties, CurrentUser currentUser, Clock clock) {
        this.userRepository = userRepository;
        this.shopService = shopService;
        this.otpService = otpService;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.userDetailsService = userDetailsService;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.transactions = transactions;
        this.events = events;
        this.appProperties = appProperties;
        this.currentUser = currentUser;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(new ObjectId().toHexString());
    }

    // ---- Register -----------------------------------------------------------------------

    /**
     * Creates shop + owner in <b>one MongoDB transaction</b>: either both documents are saved or
     * neither is. The verification email is sent after the commit by {@link AuthEmailListener}.
     */
    public RegisterShopResponse registerShop(RegisterShopRequest request) {
        String email = Normalize.email(request.email());
        rejectPasswordEqualToEmail(request.password(), email, "password");
        // Fast, friendly check. The unique index on users.email is the real guarantee (race below).
        if (userRepository.existsByEmail(email)) {
            throw emailAlreadyRegistered();
        }

        // Both ids are chosen up front because the documents reference each other.
        String shopId = new ObjectId().toHexString();
        String userId = new ObjectId().toHexString();
        // BCrypt is slow on purpose (~0.25 s). Do it before the transaction so the transaction stays short.
        String passwordHash = passwordEncoder.encode(request.password());

        ShopService.NewShop newShop = new ShopService.NewShop(
                request.shopName().trim(),
                Normalize.bdMobile(request.shopPhone()),
                request.address() == null ? null : request.address().toModel(),
                Normalize.trimToNull(request.facebookPageUrl()));

        User owner = new User();
        owner.setId(userId);
        owner.setEmail(email);
        owner.setPasswordHash(passwordHash);
        owner.setName(request.ownerName().trim());
        owner.setShopId(shopId);
        owner.setRole(Role.SHOP_OWNER);
        owner.setPermissions(EnumSet.copyOf(Role.SHOP_OWNER.defaultPermissions()));
        owner.setStatus(UserStatus.PENDING_VERIFICATION);
        owner.setTokenVersion(0);

        try {
            transactions.executeWithoutResult(tx -> {
                shopService.createForOwner(shopId, userId, newShop);
                userRepository.save(owner);
                // Delivered to @TransactionalEventListener only if the commit succeeds.
                events.publishEvent(new UserRegisteredEvent(userId, shopId, email, owner.getName()));
            });
        } catch (DuplicateKeyException e) {
            // Two registrations with the same email at the same moment: the unique index rejected
            // the second one and the transaction rolled back its shop too.
            throw emailAlreadyRegistered();
        }

        log.info("Shop {} registered by user {}", shopId, userId);
        return new RegisterShopResponse("Registration successful. We sent a 6-digit code to your email.",
                email, OtpService.RESEND_COOLDOWN.toSeconds());
    }

    // ---- Verify email -------------------------------------------------------------------

    /** Correct code → user becomes ACTIVE and is logged in right away. */
    public AuthResult verifyEmail(VerifyEmailRequest request) {
        String email = Normalize.email(request.email());
        // Unknown email and "already verified" give the same error as a wrong code.
        User user = userRepository.findByEmail(email)
                .filter(u -> u.getStatus() == UserStatus.PENDING_VERIFICATION)
                .orElseThrow(AuthService::invalidOtp);

        otpService.verify(email, OtpPurpose.EMAIL_VERIFICATION, request.code());

        Instant now = clock.instant();
        if (userRepository.activatePendingUser(user.getId(), now) == 0) {
            throw invalidOtp(); // status changed meanwhile (e.g. disabled)
        }
        userDetailsService.evictFromCache(user.getId());
        user.setStatus(UserStatus.ACTIVE);
        user.setLastLoginAt(now);
        return issueTokens(user);
    }

    /**
     * Always the same answer, immediately. Whether a code is actually sent (pending account,
     * not rate-limited) is decided in the background by {@link #sendCodeIfEligible}.
     */
    public OtpSentResponse resendVerificationCode(String rawEmail) {
        events.publishEvent(new OtpRequestedEvent(Normalize.email(rawEmail), OtpPurpose.EMAIL_VERIFICATION));
        return new OtpSentResponse(RESEND_MESSAGE, OtpService.RESEND_COOLDOWN.toSeconds());
    }

    // ---- Password reset -----------------------------------------------------------------

    /** Same idea as resend: constant answer, work happens in the background. */
    public OtpSentResponse forgotPassword(String rawEmail) {
        events.publishEvent(new OtpRequestedEvent(Normalize.email(rawEmail), OtpPurpose.PASSWORD_RESET));
        return new OtpSentResponse(FORGOT_MESSAGE, OtpService.RESEND_COOLDOWN.toSeconds());
    }

    /**
     * Correct code → new password, user ACTIVE (this also activates INVITED staff and
     * PENDING_VERIFICATION owners: the code proved they own the email), every existing token revoked.
     * The user then logs in normally.
     */
    public void resetPassword(ResetPasswordRequest request) {
        String email = Normalize.email(request.email());
        // Checked before the code, so a typo in the password does not use up a code attempt.
        rejectPasswordEqualToEmail(request.newPassword(), email, "newPassword");

        User user = userRepository.findByEmail(email)
                .filter(u -> u.getStatus() != UserStatus.DISABLED)
                .orElseThrow(AuthService::invalidOtp);

        otpService.verify(email, OtpPurpose.PASSWORD_RESET, request.code());

        String hash = passwordEncoder.encode(request.newPassword());
        if (userRepository.resetPassword(user.getId(), hash, clock.instant()) == 0) {
            throw invalidOtp(); // disabled meanwhile
        }
        userDetailsService.evictFromCache(user.getId());
        loginAttemptLimiter.reset(email);
        log.info("Password reset for user {}", user.getId());
    }

    /**
     * Background part of resend / forgot. Never throws for "not eligible" or rate limits: those
     * cases just send nothing. Called by {@link AuthEmailListener}.
     */
    public void sendCodeIfEligible(String email, OtpPurpose purpose) {
        User user = userRepository.findByEmail(email).orElse(null);
        boolean eligible = user != null && switch (purpose) {
            case EMAIL_VERIFICATION -> user.getStatus() == UserStatus.PENDING_VERIFICATION;
            case PASSWORD_RESET -> user.getStatus() != UserStatus.DISABLED;
        };
        if (!eligible) {
            return;
        }
        try {
            otpService.issue(email, purpose, user.getName());
        } catch (BusinessRuleException e) {
            // OTP_COOLDOWN / OTP_RATE_LIMITED: the limit still works (no email), it is just silent.
            log.debug("{} code for user {} not sent: {}", purpose, user.getId(), e.getCode());
        }
    }

    // ---- Login --------------------------------------------------------------------------

    public AuthResult login(LoginRequest request) {
        String email = Normalize.email(request.email());
        if (loginAttemptLimiter.isBlocked(email)) {
            throw new BusinessRuleException(HttpStatus.TOO_MANY_REQUESTS, LOGIN_RATE_LIMITED,
                    "Too many failed logins. Please try again in 15 minutes");
        }

        User user = userRepository.findByEmail(email).orElse(null);
        boolean hasPassword = user != null && user.getPasswordHash() != null;
        // Always run exactly one BCrypt check, even for unknown emails (timing).
        boolean matches = passwordEncoder.matches(request.password(),
                hasPassword ? user.getPasswordHash() : dummyPasswordHash);

        if (!hasPassword || !matches) {
            loginAttemptLimiter.recordFailure(email);
            throw invalidCredentials();
        }
        // Only reached with the correct password, so revealing "not verified" leaks nothing.
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            throw new BusinessRuleException(HttpStatus.FORBIDDEN, EMAIL_NOT_VERIFIED,
                    "Please verify your email first. Check your inbox for the code");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw invalidCredentials(); // DISABLED / INVITED: same generic answer
        }

        loginAttemptLimiter.reset(email);
        Instant now = clock.instant();
        // Atomic $set, not save(): parallel logins of the same user must not conflict on @Version.
        userRepository.updateLastLoginAt(user.getId(), now);
        user.setLastLoginAt(now);
        return issueTokens(user);
    }

    // ---- Refresh ------------------------------------------------------------------------

    /**
     * New access token (and rotated refresh token) from the refresh cookie. Role and permissions
     * are read fresh from the database, so changes made since the last login apply now.
     *
     * @param origin the request's {@code Origin} header; {@code null} for non-browser clients
     */
    public AuthResult refresh(String refreshToken, String origin) {
        // CSRF defense in depth (the cookie is already SameSite=Strict): a browser on another site
        // always sends its Origin, and the only allowed one is our own (APP_URL).
        if (origin != null && !origin.equals(appProperties.appUrl())) {
            throw new BusinessRuleException(HttpStatus.FORBIDDEN, INVALID_ORIGIN, "Request origin is not allowed");
        }
        if (refreshToken == null || refreshToken.isBlank()) {
            throw invalidRefreshToken();
        }
        RefreshTokenClaims claims;
        try {
            claims = jwtService.parseRefreshToken(refreshToken);
        } catch (InvalidTokenException e) {
            throw invalidRefreshToken();
        }
        User user = userRepository.findById(claims.userId())
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .filter(u -> u.getTokenVersion() == claims.tokenVersion()) // logged out → old version
                .orElseThrow(AuthService::invalidRefreshToken);
        return issueTokens(user);
    }

    // ---- Logout / me --------------------------------------------------------------------

    /**
     * Revokes every token of the logged-in user, on all devices: tokenVersion + 1, so the {@code ver}
     * in old access and refresh tokens no longer matches. The cache is evicted so the next request
     * with an old access token is rejected immediately, not after the cache expires.
     */
    public void logout() {
        String userId = currentUser.userId();
        userRepository.incrementTokenVersion(userId, clock.instant());
        userDetailsService.evictFromCache(userId);
    }

    /** Current user (fresh from the database) and a short summary of their shop. */
    public MeResponse me() {
        String userId = currentUser.userId();
        User user = userRepository.findById(userId).orElseThrow(() -> ResourceNotFoundException.of("User"));
        ShopSummary shop = user.getShopId() == null ? null : shopService.findSummary(user.getShopId()).orElse(null);
        return new MeResponse(UserResponse.from(user), shop);
    }

    // ---- helpers ------------------------------------------------------------------------

    private AuthResult issueTokens(User user) {
        String accessToken = jwtService.generateAccessToken(UserDetailsServiceImpl.toDetails(user));
        String refreshToken = jwtService.generateRefreshToken(user.getId(), user.getTokenVersion());
        return new AuthResult(accessToken, refreshToken, jwtService.accessTokenTtlSeconds(), UserResponse.from(user));
    }

    private static void rejectPasswordEqualToEmail(String password, String normalizedEmail, String field) {
        if (password.trim().toLowerCase(Locale.ROOT).equals(normalizedEmail)) {
            throw new FieldValidationException(field, "must not be the same as the email");
        }
    }

    private static BusinessRuleException invalidOtp() {
        return new BusinessRuleException(HttpStatus.BAD_REQUEST, OtpService.INVALID_OTP, "The code is invalid or has expired");
    }

    private static BusinessRuleException emailAlreadyRegistered() {
        return new BusinessRuleException(EMAIL_ALREADY_REGISTERED, "An account with this email already exists");
    }

    private static BusinessRuleException invalidCredentials() {
        return new BusinessRuleException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS, "Email or password is incorrect");
    }

    private static BusinessRuleException invalidRefreshToken() {
        return new BusinessRuleException(HttpStatus.UNAUTHORIZED, INVALID_REFRESH_TOKEN,
                "Session expired. Please log in again");
    }
}

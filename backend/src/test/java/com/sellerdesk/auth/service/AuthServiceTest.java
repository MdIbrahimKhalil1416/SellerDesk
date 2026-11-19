package com.sellerdesk.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sellerdesk.TestFixtures;
import com.sellerdesk.auth.dto.LoginRequest;
import com.sellerdesk.auth.dto.MeResponse;
import com.sellerdesk.auth.dto.OtpSentResponse;
import com.sellerdesk.auth.dto.RegisterShopRequest;
import com.sellerdesk.auth.dto.RegisterShopResponse;
import com.sellerdesk.auth.dto.ResetPasswordRequest;
import com.sellerdesk.auth.dto.VerifyEmailRequest;
import com.sellerdesk.auth.model.OtpPurpose;
import com.sellerdesk.auth.model.User;
import com.sellerdesk.auth.model.UserStatus;
import com.sellerdesk.auth.repository.UserRepository;
import com.sellerdesk.common.dto.AddressDto;
import com.sellerdesk.common.error.BusinessRuleException;
import com.sellerdesk.common.error.FieldValidationException;
import com.sellerdesk.common.model.DeliveryZone;
import com.sellerdesk.common.security.AccessTokenClaims;
import com.sellerdesk.common.security.AppUserDetailsService;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.common.security.JwtService;
import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import com.sellerdesk.shop.dto.ShopSummary;
import com.sellerdesk.shop.model.ShopStatus;
import com.sellerdesk.shop.service.ShopService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionOperations;

/**
 * AuthService with mocked collaborators. Transactions are replaced by
 * {@link TransactionOperations#withoutTransaction()}, and the atomic {@code @Update} repository
 * methods are mocked: their real MongoDB behavior is covered by the Testcontainers integration test.
 */
class AuthServiceTest {

    static final Instant NOW = Instant.now();

    private final UserRepository userRepository = mock(UserRepository.class);
    private final ShopService shopService = mock(ShopService.class);
    private final OtpService otpService = mock(OtpService.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final AppUserDetailsService userDetailsService = mock(AppUserDetailsService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final JwtService jwtService = TestFixtures.jwtServiceAt(NOW);
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter();

    private AuthService service;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$HASHED");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new AuthService(userRepository, shopService, otpService, jwtService, passwordEncoder,
                userDetailsService, limiter, TransactionOperations.withoutTransaction(), events,
                TestFixtures.properties(), currentUser, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---- register -----------------------------------------------------------------------

    @Test
    void registerCreatesShopAndOwnerThatPointAtEachOther() {
        RegisterShopResponse response = service.registerShop(registerRequest("  Rahim@Example.com ", "rahim2026shop"));

        ArgumentCaptor<String> shopId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> ownerId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ShopService.NewShop> newShop = ArgumentCaptor.forClass(ShopService.NewShop.class);
        verify(shopService).createForOwner(shopId.capture(), ownerId.capture(), newShop.capture());
        ArgumentCaptor<User> owner = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(owner.capture());

        User u = owner.getValue();
        assertThat(u.getId()).isEqualTo(ownerId.getValue());
        assertThat(u.getShopId()).isEqualTo(shopId.getValue());
        assertThat(u.getEmail()).isEqualTo("rahim@example.com");
        assertThat(u.getPasswordHash()).isEqualTo("$2a$12$HASHED");
        assertThat(u.getRole()).isEqualTo(Role.SHOP_OWNER);
        assertThat(u.getPermissions()).isEqualTo(EnumSet.allOf(Permission.class));
        assertThat(u.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
        assertThat(u.getTokenVersion()).isZero();

        assertThat(newShop.getValue().phone()).isEqualTo("01712345678"); // +880 prefix removed
        assertThat(newShop.getValue().address().district()).isEqualTo("Dhaka");

        verify(events).publishEvent(new UserRegisteredEvent(u.getId(), u.getShopId(), "rahim@example.com", "Rahim Uddin"));
        assertThat(response.email()).isEqualTo("rahim@example.com");
        assertThat(response.resendAfterSeconds()).isEqualTo(60);
    }

    @Test
    void registerDoesNotSendTheCodeItself() {
        service.registerShop(registerRequest("rahim@example.com", "rahim2026shop"));

        // The code is created by AuthEmailListener after the commit, not inside registration.
        verifyNoInteractions(otpService);
    }

    @Test
    void registerWithExistingEmailIs409AndCreatesNothing() {
        when(userRepository.existsByEmail("rahim@example.com")).thenReturn(true);

        assertCode(() -> service.registerShop(registerRequest("rahim@example.com", "rahim2026shop")),
                HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED");
        verifyNoInteractions(shopService);
        verify(userRepository, never()).save(any());
    }

    @Test
    void registerRaceOnUniqueEmailIndexIs409() {
        when(userRepository.save(any(User.class))).thenThrow(new DuplicateKeyException("E11000"));

        assertCode(() -> service.registerShop(registerRequest("rahim@example.com", "rahim2026shop")),
                HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED");
        verify(events, never()).publishEvent(any());
    }

    @Test
    void registerWhenShopCreationFailsDoesNotCreateOwnerOrPublishEvent() {
        doThrow(new IllegalStateException("db down")).when(shopService).createForOwner(any(), any(), any());

        assertThatThrownBy(() -> service.registerShop(registerRequest("rahim@example.com", "rahim2026shop")))
                .hasMessage("db down");
        verify(userRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void passwordEqualToEmailIsAFieldError() {
        assertThatThrownBy(() -> service.registerShop(registerRequest("rahim1@example.com", "Rahim1@example.com")))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("VALIDATION_FAILED");
                    assertThat(e.getFieldErrors()).singleElement()
                            .satisfies(fe -> assertThat(fe.field()).isEqualTo("password"));
                });
    }

    // ---- verify email -------------------------------------------------------------------

    @Test
    void verifyEmailActivatesUserAtomicallyAndReturnsTokens() {
        when(userRepository.findByEmail("rahim@example.com"))
                .thenReturn(Optional.of(user(UserStatus.PENDING_VERIFICATION, 0)));
        when(userRepository.activatePendingUser("u1", NOW)).thenReturn(1L);

        AuthService.AuthResult result = service.verifyEmail(new VerifyEmailRequest("Rahim@example.com", "123456"));

        verify(otpService).verify("rahim@example.com", OtpPurpose.EMAIL_VERIFICATION, "123456");
        verify(userRepository).activatePendingUser("u1", NOW);
        verify(userRepository, never()).save(any());
        verify(userDetailsService).evictFromCache("u1");

        AccessTokenClaims claims = jwtService.parseAccessToken(result.accessToken());
        assertThat(claims.userId()).isEqualTo("u1");
        assertThat(claims.shopId()).isEqualTo("shopA");
        assertThat(jwtService.parseRefreshToken(result.refreshToken()).userId()).isEqualTo("u1");
        assertThat(result.user().status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void verifyEmailForAlreadyActiveUserIsInvalidOtp() {
        when(userRepository.findByEmail("rahim@example.com")).thenReturn(Optional.of(user(UserStatus.ACTIVE, 0)));

        assertCode(() -> service.verifyEmail(new VerifyEmailRequest("rahim@example.com", "123456")),
                HttpStatus.BAD_REQUEST, "INVALID_OTP");
        verifyNoInteractions(otpService);
    }

    @Test
    void verifyEmailWithWrongCodeKeepsUserPending() {
        when(userRepository.findByEmail("rahim@example.com"))
                .thenReturn(Optional.of(user(UserStatus.PENDING_VERIFICATION, 0)));
        doThrow(new BusinessRuleException(HttpStatus.BAD_REQUEST, "INVALID_OTP", "x"))
                .when(otpService).verify(any(), any(), any());

        assertCode(() -> service.verifyEmail(new VerifyEmailRequest("rahim@example.com", "000000")),
                HttpStatus.BAD_REQUEST, "INVALID_OTP");
        verify(userRepository, never()).activatePendingUser(any(), any());
    }

    @Test
    void verifyEmailWhenUserStoppedBeingPendingMeanwhileIsInvalidOtp() {
        when(userRepository.findByEmail("rahim@example.com"))
                .thenReturn(Optional.of(user(UserStatus.PENDING_VERIFICATION, 0)));
        when(userRepository.activatePendingUser("u1", NOW)).thenReturn(0L);

        assertCode(() -> service.verifyEmail(new VerifyEmailRequest("rahim@example.com", "123456")),
                HttpStatus.BAD_REQUEST, "INVALID_OTP");
    }

    // ---- resend / forgot: same answer for everyone ----------------------------------------

    @Test
    void resendOnlyPublishesAnEventAndReturnsConstantAnswer() {
        OtpSentResponse response = service.resendVerificationCode(" Rahim@Example.com ");

        verify(events).publishEvent(new OtpRequestedEvent("rahim@example.com", OtpPurpose.EMAIL_VERIFICATION));
        verifyNoInteractions(userRepository, otpService); // no lookup in the request: no timing difference
        assertThat(response.resendAfterSeconds()).isEqualTo(60);
    }

    @Test
    void resendResponseIsIdenticalForAnyEmail() {
        assertThat(service.resendVerificationCode("nobody@example.com"))
                .isEqualTo(service.resendVerificationCode("rahim@example.com"));
    }

    @Test
    void forgotOnlyPublishesAnEventAndReturnsConstantAnswer() {
        OtpSentResponse a = service.forgotPassword("Rahim@example.com");
        OtpSentResponse b = service.forgotPassword("nobody@example.com");

        verify(events).publishEvent(new OtpRequestedEvent("rahim@example.com", OtpPurpose.PASSWORD_RESET));
        verify(events).publishEvent(new OtpRequestedEvent("nobody@example.com", OtpPurpose.PASSWORD_RESET));
        verifyNoInteractions(userRepository, otpService);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void backgroundVerificationCodeOnlyForPendingUsers() {
        when(userRepository.findByEmail("pending@example.com")).thenReturn(Optional.of(user(UserStatus.PENDING_VERIFICATION, 0)));
        when(userRepository.findByEmail("active@example.com")).thenReturn(Optional.of(user(UserStatus.ACTIVE, 0)));
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        service.sendCodeIfEligible("pending@example.com", OtpPurpose.EMAIL_VERIFICATION);
        service.sendCodeIfEligible("active@example.com", OtpPurpose.EMAIL_VERIFICATION);
        service.sendCodeIfEligible("nobody@example.com", OtpPurpose.EMAIL_VERIFICATION);

        verify(otpService).issue("pending@example.com", OtpPurpose.EMAIL_VERIFICATION, "Rahim Uddin");
        verify(otpService, never()).issue(eq("active@example.com"), any(), any());
        verify(otpService, never()).issue(eq("nobody@example.com"), any(), any());
    }

    @Test
    void backgroundResetCodeForEveryoneExceptDisabled() {
        for (UserStatus status : UserStatus.values()) {
            String email = status.name().toLowerCase(Locale.ROOT) + "@example.com";
            when(userRepository.findByEmail(email)).thenReturn(Optional.of(user(status, 0)));
            service.sendCodeIfEligible(email, OtpPurpose.PASSWORD_RESET);
        }

        verify(otpService).issue("active@example.com", OtpPurpose.PASSWORD_RESET, "Rahim Uddin");
        verify(otpService).issue("invited@example.com", OtpPurpose.PASSWORD_RESET, "Rahim Uddin");
        verify(otpService).issue("pending_verification@example.com", OtpPurpose.PASSWORD_RESET, "Rahim Uddin");
        verify(otpService, never()).issue(eq("disabled@example.com"), any(), any());
    }

    @Test
    void backgroundRateLimitIsSilent() {
        when(userRepository.findByEmail("rahim@example.com"))
                .thenReturn(Optional.of(user(UserStatus.PENDING_VERIFICATION, 0)));
        doThrow(new BusinessRuleException(HttpStatus.TOO_MANY_REQUESTS, "OTP_COOLDOWN", "wait"))
                .when(otpService).issue(any(), any(), any());

        // No exception: the client already got its 202, and the limit held (no new code).
        service.sendCodeIfEligible("rahim@example.com", OtpPurpose.EMAIL_VERIFICATION);
    }

    // ---- password reset -----------------------------------------------------------------

    @Test
    void resetSetsNewHashRevokesTokensAndEvictsCache() {
        when(userRepository.findByEmail("rahim@example.com")).thenReturn(Optional.of(user(UserStatus.INVITED, 2)));
        when(passwordEncoder.encode("newShop2026")).thenReturn("$2a$12$NEW");
        when(userRepository.resetPassword("u1", "$2a$12$NEW", NOW)).thenReturn(1L);

        service.resetPassword(new ResetPasswordRequest("Rahim@example.com", "123456", "newShop2026"));

        verify(otpService).verify("rahim@example.com", OtpPurpose.PASSWORD_RESET, "123456");
        // One atomic update: new hash, status ACTIVE, tokenVersion + 1 (see UserRepository).
        verify(userRepository).resetPassword("u1", "$2a$12$NEW", NOW);
        verify(userDetailsService).evictFromCache("u1");
    }

    @Test
    void resetClearsFailedLoginCounter() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("rahim@example.com");
        }
        when(userRepository.findByEmail("rahim@example.com")).thenReturn(Optional.of(user(UserStatus.ACTIVE, 0)));
        when(userRepository.resetPassword(eq("u1"), any(), eq(NOW))).thenReturn(1L);

        service.resetPassword(new ResetPasswordRequest("rahim@example.com", "123456", "newShop2026"));

        assertThat(limiter.isBlocked("rahim@example.com")).isFalse();
    }

    @Test
    void resetForDisabledOrUnknownUserIsInvalidOtpWithoutCheckingCode() {
        when(userRepository.findByEmail("rahim@example.com")).thenReturn(Optional.of(user(UserStatus.DISABLED, 0)));
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertCode(() -> service.resetPassword(new ResetPasswordRequest("rahim@example.com", "123456", "newShop2026")),
                HttpStatus.BAD_REQUEST, "INVALID_OTP");
        assertCode(() -> service.resetPassword(new ResetPasswordRequest("nobody@example.com", "123456", "newShop2026")),
                HttpStatus.BAD_REQUEST, "INVALID_OTP");
        verifyNoInteractions(otpService);
    }

    @Test
    void resetWithWrongCodeChangesNothing() {
        when(userRepository.findByEmail("rahim@example.com")).thenReturn(Optional.of(user(UserStatus.ACTIVE, 0)));
        doThrow(new BusinessRuleException(HttpStatus.BAD_REQUEST, "INVALID_OTP", "x"))
                .when(otpService).verify(any(), any(), any());

        assertCode(() -> service.resetPassword(new ResetPasswordRequest("rahim@example.com", "000000", "newShop2026")),
                HttpStatus.BAD_REQUEST, "INVALID_OTP");
        verify(userRepository, never()).resetPassword(any(), any(), any());
        verify(userDetailsService, never()).evictFromCache(any());
    }

    @Test
    void resetPasswordEqualToEmailIsFieldErrorBeforeUsingACodeAttempt() {
        assertThatThrownBy(() -> service.resetPassword(
                new ResetPasswordRequest("rahim1@example.com", "123456", "rahim1@example.com")))
                .isInstanceOfSatisfying(FieldValidationException.class, e ->
                        assertThat(e.getFieldErrors()).singleElement()
                                .satisfies(fe -> assertThat(fe.field()).isEqualTo("newPassword")));
        verifyNoInteractions(otpService);
    }

    // ---- login --------------------------------------------------------------------------

    @Test
    void loginWithCorrectPasswordReturnsTokensWithCurrentPermissions() {
        when(userRepository.findByEmail("rahim@example.com")).thenReturn(Optional.of(user(UserStatus.ACTIVE, 3)));
        when(passwordEncoder.matches("rahim2026shop", "$2a$12$REAL")).thenReturn(true);

        AuthService.AuthResult result = service.login(new LoginRequest("rahim@example.com", "rahim2026shop"));

        AccessTokenClaims claims = jwtService.parseAccessToken(result.accessToken());
        assertThat(claims.tokenVersion()).isEqualTo(3);
        assertThat(claims.role()).isEqualTo(Role.SHOP_OWNER);
        assertThat(claims.permissions()).isEqualTo(EnumSet.allOf(Permission.class));
        assertThat(result.expiresIn()).isEqualTo(900);
    }

    @Test
    void loginUpdatesLastLoginAtAtomicallyInsteadOfSavingTheVersionedDocument() {
        when(userRepository.findByEmail("rahim@example.com")).thenReturn(Optional.of(user(UserStatus.ACTIVE, 0)));
        when(passwordEncoder.matches("rahim2026shop", "$2a$12$REAL")).thenReturn(true);

        service.login(new LoginRequest("rahim@example.com", "rahim2026shop"));
        service.login(new LoginRequest("rahim@example.com", "rahim2026shop")); // e.g. a second device

        verify(userRepository, times(2)).updateLastLoginAt("u1", NOW);
        verify(userRepository, never()).save(any()); // save() would check @Version → possible 409
    }

    @Test
    void wrongPasswordIs401() {
        when(userRepository.findByEmail("rahim@example.com")).thenReturn(Optional.of(user(UserStatus.ACTIVE, 0)));
        when(passwordEncoder.matches(any(), any())).thenReturn(false);

        assertCode(() -> service.login(new LoginRequest("rahim@example.com", "wrong1234")),
                HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        verify(userRepository, never()).updateLastLoginAt(any(), any());
    }

    @Test
    void unknownEmailIs401AndStillRunsOneBcryptCheck() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertCode(() -> service.login(new LoginRequest("nobody@example.com", "whatever1")),
                HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        verify(passwordEncoder).matches(eq("whatever1"), any()); // dummy hash: same timing as a real user
    }

    @Test
    void sixthFailedLoginIsRateLimited() {
        when(userRepository.findByEmail("rahim@example.com")).thenReturn(Optional.of(user(UserStatus.ACTIVE, 0)));
        when(passwordEncoder.matches(any(), any())).thenReturn(false);
        LoginRequest wrong = new LoginRequest("rahim@example.com", "wrong1234");

        for (int i = 0; i < 5; i++) {
            assertCode(() -> service.login(wrong), HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        }
        // Even the correct password is refused now.
        when(passwordEncoder.matches("rahim2026shop", "$2a$12$REAL")).thenReturn(true);
        assertCode(() -> service.login(new LoginRequest("rahim@example.com", "rahim2026shop")),
                HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED");
    }

    @Test
    void unverifiedUserWithCorrectPasswordIs403EmailNotVerified() {
        when(userRepository.findByEmail("rahim@example.com"))
                .thenReturn(Optional.of(user(UserStatus.PENDING_VERIFICATION, 0)));
        when(passwordEncoder.matches("rahim2026shop", "$2a$12$REAL")).thenReturn(true);

        assertCode(() -> service.login(new LoginRequest("rahim@example.com", "rahim2026shop")),
                HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED");
    }

    @Test
    void disabledUserIs401LikeAWrongPassword() {
        when(userRepository.findByEmail("rahim@example.com")).thenReturn(Optional.of(user(UserStatus.DISABLED, 0)));
        when(passwordEncoder.matches("rahim2026shop", "$2a$12$REAL")).thenReturn(true);

        assertCode(() -> service.login(new LoginRequest("rahim@example.com", "rahim2026shop")),
                HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
    }

    // ---- refresh ------------------------------------------------------------------------

    @Test
    void refreshReturnsNewTokensWithPermissionsFromDatabase() {
        User user = user(UserStatus.ACTIVE, 2);
        user.setRole(Role.MODERATOR);
        user.setPermissions(EnumSet.of(Permission.ORDER_VIEW));
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));

        AuthService.AuthResult result = service.refresh(jwtService.generateRefreshToken("u1", 2), TestFixtures.APP_URL);

        AccessTokenClaims claims = jwtService.parseAccessToken(result.accessToken());
        assertThat(claims.role()).isEqualTo(Role.MODERATOR);
        assertThat(claims.permissions()).containsExactly(Permission.ORDER_VIEW);
        assertThat(result.refreshToken()).isNotBlank();
    }

    @Test
    void refreshWithOldTokenVersionIs401() {
        when(userRepository.findById("u1")).thenReturn(Optional.of(user(UserStatus.ACTIVE, 3)));

        assertCode(() -> service.refresh(jwtService.generateRefreshToken("u1", 2), null),
                HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN");
    }

    @Test
    void refreshForDisabledUserIs401() {
        when(userRepository.findById("u1")).thenReturn(Optional.of(user(UserStatus.DISABLED, 0)));

        assertCode(() -> service.refresh(jwtService.generateRefreshToken("u1", 0), null),
                HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN");
    }

    @Test
    void refreshWithoutCookieOrWithAccessTokenIs401() {
        String accessToken = jwtService.generateAccessToken("u1", "shopA", Role.SHOP_OWNER, EnumSet.noneOf(Permission.class), 0);

        assertCode(() -> service.refresh(null, null), HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN");
        assertCode(() -> service.refresh(accessToken, null), HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN");
    }

    @Test
    void refreshFromForeignOriginIs403() {
        assertCode(() -> service.refresh(jwtService.generateRefreshToken("u1", 0), "https://evil.example.com"),
                HttpStatus.FORBIDDEN, "INVALID_ORIGIN");
        verifyNoInteractions(userRepository);
    }

    // ---- logout / me --------------------------------------------------------------------

    @Test
    void logoutIncrementsTokenVersionAndEvictsCache() {
        when(currentUser.userId()).thenReturn("u1");

        service.logout();

        verify(userRepository).incrementTokenVersion("u1", NOW);
        verify(userDetailsService).evictFromCache("u1");
    }

    @Test
    void refreshTokenFromBeforeLogoutNoLongerWorks() {
        // Before logout: tokenVersion 0, token carries ver 0. After logout the DB says 1.
        String oldRefresh = jwtService.generateRefreshToken("u1", 0);
        when(userRepository.findById("u1")).thenReturn(Optional.of(user(UserStatus.ACTIVE, 1)));

        assertCode(() -> service.refresh(oldRefresh, null), HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN");
    }

    @Test
    void meReturnsUserAndShopSummary() {
        when(currentUser.userId()).thenReturn("u1");
        when(userRepository.findById("u1")).thenReturn(Optional.of(user(UserStatus.ACTIVE, 0)));
        when(shopService.findSummary("shopA")).thenReturn(Optional.of(
                new ShopSummary("shopA", "Rahim Fashion", ShopStatus.ACTIVE)));

        MeResponse me = service.me();

        assertThat(me.user().id()).isEqualTo("u1");
        assertThat(me.user().role()).isEqualTo(Role.SHOP_OWNER);
        assertThat(me.shop().name()).isEqualTo("Rahim Fashion");
    }

    @Test
    void meForPlatformAdminHasNoShop() {
        User admin = user(UserStatus.ACTIVE, 0);
        admin.setShopId(null);
        admin.setRole(Role.PLATFORM_ADMIN);
        when(currentUser.userId()).thenReturn("u1");
        when(userRepository.findById("u1")).thenReturn(Optional.of(admin));

        assertThat(service.me().shop()).isNull();
        verifyNoInteractions(shopService);
    }

    // ---- helpers ------------------------------------------------------------------------

    private static RegisterShopRequest registerRequest(String email, String password) {
        return new RegisterShopRequest("Rahim Uddin", email, password, "Rahim Fashion", "+8801712345678",
                new AddressDto("House 1, Road 2", null, "Dhaka", DeliveryZone.INSIDE_DHAKA, null),
                "https://facebook.com/rahimfashion");
    }

    private static User user(UserStatus status, int tokenVersion) {
        User user = new User();
        user.setId("u1");
        user.setShopId("shopA");
        user.setEmail("rahim@example.com");
        user.setName("Rahim Uddin");
        user.setPasswordHash("$2a$12$REAL");
        user.setRole(Role.SHOP_OWNER);
        user.setPermissions(EnumSet.allOf(Permission.class));
        user.setStatus(status);
        user.setTokenVersion(tokenVersion);
        return user;
    }

    private static void assertCode(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessRuleException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }
}

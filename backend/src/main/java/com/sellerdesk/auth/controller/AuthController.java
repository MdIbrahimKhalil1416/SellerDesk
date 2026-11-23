package com.sellerdesk.auth.controller;

import com.sellerdesk.auth.dto.AuthResponse;
import com.sellerdesk.auth.dto.EmailRequest;
import com.sellerdesk.auth.dto.LoginRequest;
import com.sellerdesk.auth.dto.MeResponse;
import com.sellerdesk.auth.dto.OtpSentResponse;
import com.sellerdesk.auth.dto.RegisterShopRequest;
import com.sellerdesk.auth.dto.RegisterShopResponse;
import com.sellerdesk.auth.dto.ResetPasswordRequest;
import com.sellerdesk.auth.dto.TokenResponse;
import com.sellerdesk.auth.dto.VerifyEmailRequest;
import com.sellerdesk.auth.service.AuthService;
import com.sellerdesk.common.config.OpenApiConfig;
import com.sellerdesk.common.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auth endpoints (docs/API.md §1). All public except logout and me. Thin: validate, call the service, shape the response.
 * Errors use the ApiError format (see the "ApiError" schema in Swagger).
 */
@Tag(name = "Auth", description = "Register, verify email, login, refresh, logout, password reset")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookies refreshTokenCookies;

    @Operation(summary = "Register a new shop and its owner",
            description = "Creates the shop and a SHOP_OWNER user (PENDING_VERIFICATION) together, then emails a 6-digit code.")
    @ApiResponse(responseCode = "201", description = "Registered; verification code is being sent")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "EMAIL_ALREADY_REGISTERED", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/register-shop")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterShopResponse registerShop(@Valid @RequestBody RegisterShopRequest request) {
        return authService.registerShop(request);
    }

    @Operation(summary = "Verify email with the 6-digit code",
            description = "Activates the account and logs in. Sets the refresh-token cookie.")
    @ApiResponse(responseCode = "200", description = "Verified and logged in")
    @ApiResponse(responseCode = "400", description = "INVALID_OTP or VALIDATION_FAILED", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/verify-email")
    public ResponseEntity<AuthResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        return withRefreshCookie(authService.verifyEmail(request));
    }

    @Operation(summary = "Send a new verification code",
            description = "Same response whether or not the email has an account. One code per 60 s, max 5 per hour.")
    @ApiResponse(responseCode = "202", description = "Accepted")
    @ApiResponse(responseCode = "429", description = "OTP_COOLDOWN or OTP_RATE_LIMITED", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/verify-email/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OtpSentResponse resendVerificationCode(@Valid @RequestBody EmailRequest request) {
        return authService.resendVerificationCode(request.email());
    }

    @Operation(summary = "Log in with email and password", description = "Sets the refresh-token cookie.")
    @ApiResponse(responseCode = "200", description = "Logged in")
    @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "429", description = "LOGIN_RATE_LIMITED", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return withRefreshCookie(authService.login(request));
    }

    @Operation(summary = "Get a new access token",
            description = "Uses the HttpOnly refresh cookie (send requests with credentials). Rotates the cookie.")
    @ApiResponse(responseCode = "200", description = "New access token")
    @ApiResponse(responseCode = "401", description = "INVALID_REFRESH_TOKEN", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "INVALID_ORIGIN", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @Parameter(in = ParameterIn.COOKIE, name = RefreshTokenCookies.NAME, hidden = true)
            @CookieValue(name = RefreshTokenCookies.NAME, required = false) String refreshToken,
            @Parameter(hidden = true) @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin) {
        AuthService.AuthResult result = authService.refresh(refreshToken, origin);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.create(result.refreshToken()).toString())
                .body(new TokenResponse(result.accessToken(), result.expiresIn()));
    }

    @Operation(summary = "Log out on all devices",
            description = "Revokes every access and refresh token of the user (tokenVersion + 1) and deletes the refresh cookie.")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @ApiResponse(responseCode = "204", description = "Logged out")
    @ApiResponse(responseCode = "401", description = "Missing, expired or invalid access token", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> logout() {
        authService.logout();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.clear().toString())
                .build();
    }

    @Operation(summary = "Current user and shop summary")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @ApiResponse(responseCode = "200", description = "The logged-in user; shop is null for a platform admin")
    @ApiResponse(responseCode = "401", description = "Missing, expired or invalid access token", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public MeResponse me() {
        return authService.me();
    }

    @Operation(summary = "Request a password reset code",
            description = "Same 202 response for every email, including unknown, disabled or rate-limited ones.")
    @ApiResponse(responseCode = "202", description = "Accepted")
    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OtpSentResponse forgotPassword(@Valid @RequestBody EmailRequest request) {
        return authService.forgotPassword(request.email());
    }

    @Operation(summary = "Set a new password with the emailed code",
            description = "Revokes all existing tokens and activates PENDING_VERIFICATION users. Log in afterwards.")
    @ApiResponse(responseCode = "204", description = "Password changed")
    @ApiResponse(responseCode = "400", description = "INVALID_OTP or VALIDATION_FAILED", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
    }

    private ResponseEntity<AuthResponse> withRefreshCookie(AuthService.AuthResult result) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.create(result.refreshToken()).toString())
                .body(new AuthResponse(result.accessToken(), result.expiresIn(), result.user()));
    }
}

package com.sellerdesk.staff.controller;

import com.sellerdesk.auth.controller.RefreshTokenCookies;
import com.sellerdesk.auth.dto.AuthResponse;
import com.sellerdesk.auth.service.AuthService;
import com.sellerdesk.common.error.ApiError;
import com.sellerdesk.staff.dto.AcceptInvitationRequest;
import com.sellerdesk.staff.dto.InvitationPreviewResponse;
import com.sellerdesk.staff.dto.InvitationTokenRequest;
import com.sellerdesk.staff.service.InvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoints of the accept-invite page (docs/modules/STAFF.md §3.5). Under {@code /auth}
 * because accepting logs the person in, and the refresh cookie only travels to {@code /api/v1/auth}.
 * Listed in {@code SecurityConfig.PUBLIC_PATHS}: the token is the proof.
 */
@Tag(name = "Staff invitations (public)", description = "Preview and accept an invitation from the email link")
@RestController
@RequestMapping("/api/v1/auth/invitations")
@RequiredArgsConstructor
public class InvitationAcceptController {

    private final InvitationService invitationService;
    private final RefreshTokenCookies refreshTokenCookies;

    @Operation(summary = "What an invite link is for",
            description = "Shop, role and inviter, so the page can say \"Join Shop as Moderator\". Token in the body, never in the URL.")
    @ApiResponse(responseCode = "200", description = "Valid invitation")
    @ApiResponse(responseCode = "400", description = "INVALID_INVITATION or INVITATION_EXPIRED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/preview")
    public InvitationPreviewResponse preview(@Valid @RequestBody InvitationTokenRequest request) {
        return invitationService.preview(request.token());
    }

    @Operation(summary = "Accept an invitation",
            description = "Creates the ACTIVE account with the invitation's shop, role and permissions and logs in "
                    + "(sets the refresh-token cookie), like verify-email.")
    @ApiResponse(responseCode = "200", description = "Joined and logged in")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, INVALID_INVITATION or INVITATION_EXPIRED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "EMAIL_ALREADY_REGISTERED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/accept")
    public ResponseEntity<AuthResponse> accept(@Valid @RequestBody AcceptInvitationRequest request) {
        AuthService.AuthResult result = invitationService.accept(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.create(result.refreshToken()).toString())
                .body(new AuthResponse(result.accessToken(), result.expiresIn(), result.user()));
    }
}

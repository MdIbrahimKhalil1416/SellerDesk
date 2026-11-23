package com.sellerdesk.staff.controller;

import com.sellerdesk.common.config.OpenApiConfig;
import com.sellerdesk.common.dto.PageResponse;
import com.sellerdesk.common.error.ApiError;
import com.sellerdesk.staff.dto.InvitationResponse;
import com.sellerdesk.staff.dto.InviteStaffRequest;
import com.sellerdesk.staff.dto.PermissionCatalogResponse;
import com.sellerdesk.staff.dto.StaffResponse;
import com.sellerdesk.staff.dto.StaffRow;
import com.sellerdesk.staff.dto.StaffStatusRequest;
import com.sellerdesk.staff.dto.UpdateStaffRequest;
import com.sellerdesk.staff.service.InvitationService;
import com.sellerdesk.staff.service.StaffRules;
import com.sellerdesk.staff.service.StaffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff management (docs/API.md §3, docs/modules/STAFF.md §3). Every endpoint needs
 * {@code STAFF_MANAGE}; the shop always comes from the JWT.
 *
 * <p>Thin: check the permission ({@code @PreAuthorize}), validate ({@code @Valid}), call the service.
 */
@Tag(name = "Staff", description = "Invite staff, change their role and permissions, deactivate or reactivate them")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/v1/staff")
@RequiredArgsConstructor
public class StaffController {

    static final String STAFF_MANAGE = "hasAuthority('STAFF_MANAGE')";

    private final StaffService staffService;
    private final InvitationService invitationService;

    // ---- Members --------------------------------------------------------------------------

    @Operation(summary = "List members and pending invitations",
            description = "One list: owner first, then pending invitations (newest first), then members by name. "
                    + "`q` is a case-insensitive 'contains' on name or email.")
    @ApiResponse(responseCode = "200", description = "A page of staff rows")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (unknown role or status, page/size out of range)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN: missing STAFF_MANAGE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping
    @PreAuthorize(STAFF_MANAGE)
    public PageResponse<StaffRow> list(
            @Parameter(description = "Part of a name or email") @RequestParam(required = false) @Size(max = 100) String q,
            @Parameter(description = "SHOP_OWNER, MODERATOR, PACKER or ACCOUNTANT")
            @RequestParam(required = false) @Pattern(regexp = StaffRules.LIST_ROLE_REGEX) String role,
            @Parameter(description = "ACTIVE, DISABLED, INVITED (pending) or EXPIRED (pending, expired). Omitted: all")
            @RequestParam(required = false) @Pattern(regexp = StaffRules.LIST_STATUS_REGEX) String status,
            @Parameter(description = "0-based page") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size, max 100") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return staffService.list(q, role, status, page, size);
    }

    @Operation(summary = "Permission catalog",
            description = "Every permission with its description, and the default permissions of each staff role.")
    @ApiResponse(responseCode = "200", description = "The catalog")
    @ApiResponse(responseCode = "403", description = "FORBIDDEN: missing STAFF_MANAGE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/permissions")
    @PreAuthorize(STAFF_MANAGE)
    public PermissionCatalogResponse permissions() {
        return staffService.permissionCatalog();
    }

    @Operation(summary = "Get a member")
    @ApiResponse(responseCode = "200", description = "The member")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (unknown, or of another shop)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{userId}")
    @PreAuthorize(STAFF_MANAGE)
    public StaffResponse get(@PathVariable String userId) {
        return staffService.get(userId);
    }

    @Operation(summary = "Change role and/or permissions",
            description = "A new role without `permissions` resets them to the role's defaults. "
                    + "Any real change ends the member's sessions (they log in again with the new rights).")
    @ApiResponse(responseCode = "200", description = "Updated (or unchanged)")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (role or unknown permission)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN, OWNER_READ_ONLY, CANNOT_MODIFY_SELF or PERMISSION_NOT_GRANTABLE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "CONCURRENT_MODIFICATION",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PatchMapping("/{userId}")
    @PreAuthorize(STAFF_MANAGE)
    public StaffResponse update(@PathVariable String userId, @Valid @RequestBody UpdateStaffRequest request) {
        return staffService.update(userId, request);
    }

    @Operation(summary = "Deactivate or reactivate a member",
            description = "DISABLED ends all sessions; the member can no longer log in or refresh. "
                    + "ACTIVE keeps role and permissions. Same status as now: no change.")
    @ApiResponse(responseCode = "200", description = "Status set")
    @ApiResponse(responseCode = "403", description = "FORBIDDEN, OWNER_READ_ONLY or CANNOT_MODIFY_SELF",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "CONCURRENT_MODIFICATION",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PatchMapping("/{userId}/status")
    @PreAuthorize(STAFF_MANAGE)
    public StaffResponse changeStatus(@PathVariable String userId, @Valid @RequestBody StaffStatusRequest request) {
        return staffService.changeStatus(userId, request);
    }

    // ---- Invitations ----------------------------------------------------------------------

    @Operation(summary = "Invite someone by email",
            description = "Emails a link valid for 7 days (sent in the background after the save). "
                    + "Without `permissions` the role's defaults are used. An expired invitation for the same email is reused.")
    @ApiResponse(responseCode = "201", description = "Invitation created")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN or PERMISSION_NOT_GRANTABLE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "ALREADY_A_MEMBER, ALREADY_INVITED or EMAIL_ALREADY_REGISTERED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(STAFF_MANAGE)
    public InvitationResponse invite(@Valid @RequestBody InviteStaffRequest request) {
        return invitationService.invite(request);
    }

    @Operation(summary = "Resend an invitation",
            description = "New link (the old one stops working) and a new 7-day expiry. Works for expired invitations.")
    @ApiResponse(responseCode = "200", description = "Sent again")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "INVITATION_NOT_PENDING",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "429", description = "INVITE_RESEND_TOO_SOON (60 s) or INVITE_RESEND_LIMIT (5 per 24 h); see Retry-After",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/invitations/{id}/resend")
    @PreAuthorize(STAFF_MANAGE)
    public InvitationResponse resend(@PathVariable String id) {
        return invitationService.resend(id);
    }

    @Operation(summary = "Cancel an invitation", description = "The link stops working.")
    @ApiResponse(responseCode = "204", description = "Cancelled")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "INVITATION_NOT_PENDING",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @DeleteMapping("/invitations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(STAFF_MANAGE)
    public void cancel(@PathVariable String id) {
        invitationService.cancel(id);
    }
}

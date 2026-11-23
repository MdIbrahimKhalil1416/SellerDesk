package com.sellerdesk.staff.dto;

import com.sellerdesk.staff.service.StaffRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Invite someone by email. No {@code shopId}: the shop is the inviter's (from the JWT).
 *
 * <p>{@code role} and {@code permissions} are plain strings, not enums, so an unknown value gives
 * {@code 400 VALIDATION_FAILED} with a field error instead of an unreadable-JSON error.
 */
public record InviteStaffRequest(
        @Schema(example = "karim@example.com")
        @NotBlank @Email @Size(max = 254) String email,

        @Schema(example = "MODERATOR", allowableValues = {"MODERATOR", "PACKER", "ACCOUNTANT"})
        @NotBlank @Pattern(regexp = StaffRules.STAFF_ROLE_REGEX, message = "must be MODERATOR, PACKER or ACCOUNTANT")
        String role,

        @Schema(description = "Optional. Omitted: the role's default permissions")
        @Size(max = 50) List<String> permissions) {
}

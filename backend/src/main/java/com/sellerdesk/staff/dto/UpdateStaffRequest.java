package com.sellerdesk.staff.dto;

import com.sellerdesk.staff.service.StaffRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Change a member's role and/or permissions. Both optional.
 * A new role without {@code permissions} → that role's default permissions.
 */
public record UpdateStaffRequest(
        @Schema(example = "PACKER", allowableValues = {"MODERATOR", "PACKER", "ACCOUNTANT"})
        @Pattern(regexp = StaffRules.STAFF_ROLE_REGEX, message = "must be MODERATOR, PACKER or ACCOUNTANT")
        String role,

        @Schema(description = "The complete new list (not a diff)")
        @Size(max = 50) List<String> permissions) {
}

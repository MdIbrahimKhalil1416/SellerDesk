package com.sellerdesk.staff.dto;

import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * One row of {@code GET /staff}: a member or a pending invitation (docs/modules/STAFF.md §3.1).
 *
 * @param id     userId for MEMBER, invitationId for INVITATION
 * @param status ACTIVE / DISABLED (members), INVITED / EXPIRED (invitations)
 */
public record StaffRow(
        @Schema(allowableValues = {"MEMBER", "INVITATION"}) String kind,
        @Schema(example = "6745a1b2c3d4e5f607182930") String id,
        @Schema(example = "Rahim Uddin", description = "null for INVITATION") String name,
        @Schema(example = "rahim@example.com") String email,
        Role role,
        List<Permission> permissions,
        @Schema(allowableValues = {"ACTIVE", "DISABLED", "INVITED", "EXPIRED"}) String status,
        @Schema(description = "MEMBER only, may be null") Instant lastLoginAt,
        @Schema(description = "INVITATION only") Instant invitedAt,
        @Schema(description = "INVITATION only") Instant expiresAt,
        @Schema(description = "INVITATION only") Instant lastSentAt) {

    public static final String MEMBER = "MEMBER";
    public static final String INVITATION = "INVITATION";
}

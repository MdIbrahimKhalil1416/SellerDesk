package com.sellerdesk.staff.dto;

import com.sellerdesk.common.security.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** What the accept page shows before the form: "Join (shop) as (role)". */
public record InvitationPreviewResponse(
        @Schema(example = "karim@example.com") String email,
        @Schema(example = "Rahim Fashion") String shopName,
        Role role,
        @Schema(example = "Rahim Uddin") String inviterName,
        Instant expiresAt) {
}

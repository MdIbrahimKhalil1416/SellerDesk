package com.sellerdesk.staff.dto;

import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import com.sellerdesk.staff.model.InvitationStatus;
import com.sellerdesk.staff.model.StaffInvitation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** An invitation as the inviter sees it. Never contains the token or its hash. */
public record InvitationResponse(
        @Schema(example = "6745c3d4e5f60718293a4b5c") String id,
        @Schema(example = "karim@example.com") String email,
        Role role,
        List<Permission> permissions,
        InvitationStatus status,
        Instant expiresAt,
        Instant lastSentAt,
        Instant createdAt) {

    public static InvitationResponse from(StaffInvitation invitation) {
        return new InvitationResponse(invitation.getId(), invitation.getEmail(), invitation.getRole(),
                invitation.getPermissions().stream().sorted().toList(), invitation.getStatus(),
                invitation.getExpiresAt(), invitation.getLastSentAt(), invitation.getCreatedAt());
    }
}

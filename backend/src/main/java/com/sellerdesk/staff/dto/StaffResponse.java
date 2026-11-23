package com.sellerdesk.staff.dto;

import com.sellerdesk.auth.model.User;
import com.sellerdesk.auth.model.UserStatus;
import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** A member of the shop as the staff screen shows it. No passwordHash, no tokenVersion. */
public record StaffResponse(
        @Schema(example = "6745a1b2c3d4e5f607182930") String id,
        @Schema(example = "Rahim Uddin") String name,
        @Schema(example = "rahim@example.com") String email,
        String phone,
        Role role,
        List<Permission> permissions,
        UserStatus status,
        Instant lastLoginAt,
        Instant createdAt,
        @Schema(description = "Who invited this member; null for the owner") InvitedBy invitedBy) {

    /** @param name null if that user no longer exists */
    public record InvitedBy(String id, String name) {
    }

    public static StaffResponse from(User user, InvitedBy invitedBy) {
        return new StaffResponse(user.getId(), user.getName(), user.getEmail(), user.getPhone(), user.getRole(),
                user.getPermissions().stream().sorted().toList(), user.getStatus(), user.getLastLoginAt(),
                user.getCreatedAt(), invitedBy);
    }
}

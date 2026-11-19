package com.sellerdesk.auth.dto;

import com.sellerdesk.auth.model.User;
import com.sellerdesk.auth.model.UserStatus;
import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A user as the API shows it. Deliberately has no passwordHash or tokenVersion. */
public record UserResponse(
        @Schema(example = "6745a1b2c3d4e5f607182930") String id,
        @Schema(example = "Rahim Uddin") String name,
        @Schema(example = "rahim@example.com") String email,
        String phone,
        Role role,
        List<Permission> permissions,
        UserStatus status) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getPhone(),
                user.getRole(), user.getPermissions().stream().sorted().toList(), user.getStatus());
    }
}

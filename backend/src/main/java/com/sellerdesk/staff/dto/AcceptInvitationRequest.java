package com.sellerdesk.staff.dto;

import com.sellerdesk.auth.dto.ValidPassword;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Accept an invitation: the invited person picks their name and password. No email, shop or role
 * fields: those come from the invitation.
 */
public record AcceptInvitationRequest(
        @Schema(description = "Token from the invite link") @NotBlank @Size(max = 100) String token,
        @Schema(example = "Karim Hossain") @NotBlank @Size(max = 100) String name,
        @Schema(example = "karim2026pass", description = "8-72 characters, at least one letter and one digit")
        @ValidPassword String password) {

    /** Never print the token or the password. */
    @Override
    public String toString() {
        return "AcceptInvitationRequest[name=" + name + "]";
    }
}

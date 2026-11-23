package com.sellerdesk.staff.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The token from the invite link ({@code #token=...}). In the body, so it never lands in access logs. */
public record InvitationTokenRequest(
        @Schema(description = "Token from the invite link") @NotBlank @Size(max = 100) String token) {

    /** Never print the token. */
    @Override
    public String toString() {
        return "InvitationTokenRequest[token=***]";
    }
}

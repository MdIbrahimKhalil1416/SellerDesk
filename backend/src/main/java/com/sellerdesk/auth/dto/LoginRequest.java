package com.sellerdesk.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login. The password is only checked for presence and a sane maximum length here; the strength
 * rules apply when a password is <b>set</b>, not when it is typed in.
 */
public record LoginRequest(
        @Schema(example = "rahim@example.com") @NotBlank @Email @Size(max = 254) String email,
        @Schema(example = "rahim2026shop") @NotBlank @Size(max = 200) String password) {

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + "]";
    }
}

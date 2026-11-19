package com.sellerdesk.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Set a new password with the emailed code. Same password rules as registration. */
public record ResetPasswordRequest(
        @Schema(example = "rahim@example.com") @NotBlank @Email @Size(max = 254) String email,
        @Schema(example = "123456") @NotBlank @Pattern(regexp = "^\\d{6}$", message = "must be 6 digits") String code,
        @Schema(example = "newShop2026", description = "8-72 characters, at least one letter and one digit")
        @ValidPassword String newPassword) {

    @Override
    public String toString() {
        return "ResetPasswordRequest[email=" + email + "]";
    }
}

package com.sellerdesk.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerifyEmailRequest(
        @Schema(example = "rahim@example.com") @NotBlank @Email @Size(max = 254) String email,
        @Schema(example = "123456") @NotBlank @Pattern(regexp = "^\\d{6}$", message = "must be 6 digits") String code) {
}

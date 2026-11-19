package com.sellerdesk.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body with only an email, e.g. "send me a new code". */
public record EmailRequest(
        @Schema(example = "rahim@example.com") @NotBlank @Email @Size(max = 254) String email) {
}

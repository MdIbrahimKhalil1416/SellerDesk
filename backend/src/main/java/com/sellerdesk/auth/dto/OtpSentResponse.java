package com.sellerdesk.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Same response whether or not the email has an account, so emails cannot be probed. */
public record OtpSentResponse(
        @Schema(example = "If this email is waiting for verification, a new code has been sent.") String message,
        @Schema(example = "60") long resendAfterSeconds) {
}

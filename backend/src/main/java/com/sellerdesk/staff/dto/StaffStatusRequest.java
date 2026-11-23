package com.sellerdesk.staff.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Deactivate ({@code DISABLED}) or reactivate ({@code ACTIVE}) a member. */
public record StaffStatusRequest(
        @Schema(example = "DISABLED", allowableValues = {"ACTIVE", "DISABLED"})
        @NotBlank @Pattern(regexp = "ACTIVE|DISABLED", message = "must be ACTIVE or DISABLED") String status) {
}

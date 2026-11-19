package com.sellerdesk.auth.service;

import com.sellerdesk.auth.model.OtpPurpose;

/**
 * "Someone asked for a code for this email" (resend verification, forgot password).
 *
 * <p>Handled on a background thread. The HTTP request only publishes this event and returns, so
 * the response is identical, and takes the same time, whether the email has an account, is
 * eligible, or is rate-limited. That is what stops "which emails are registered?" probing.
 */
public record OtpRequestedEvent(String email, OtpPurpose purpose) {
}

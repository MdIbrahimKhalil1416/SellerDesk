package com.sellerdesk.auth.service;

import com.sellerdesk.auth.model.OtpPurpose;

/**
 * "A code was created, please email it." Published by {@link OtpService}, handled by
 * {@link AuthEmailListener} on a background thread.
 *
 * <p>Carries the plain code because the email needs it. It lives only in memory and is never
 * logged: {@link #toString()} hides it.
 */
public record OtpIssuedEvent(String email, String recipientName, OtpPurpose purpose, String code) {

    @Override
    public String toString() {
        return "OtpIssuedEvent[purpose=" + purpose + ", code=******]";
    }
}

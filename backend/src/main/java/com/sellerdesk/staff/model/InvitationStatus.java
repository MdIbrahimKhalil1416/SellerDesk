package com.sellerdesk.staff.model;

/**
 * Stored status of a staff invitation (docs/modules/STAFF.md §2).
 *
 * <p>"Expired" is not stored: it is {@link #PENDING} with {@code expiresAt} in the past. Nothing
 * has to run at the moment of expiry, and resend can simply set a new {@code expiresAt}.
 */
public enum InvitationStatus {
    PENDING,
    ACCEPTED,
    CANCELLED
}

package com.sellerdesk.auth.model;

/**
 * Lifecycle of a user account (docs/SCHEMA.md §3). Only {@link #ACTIVE} users can log in.
 *
 * <p>Staff never have a "not yet joined" status: a pending invitation lives in
 * {@code staff_invitations}, and the user is created {@link #ACTIVE} when it is accepted.
 */
public enum UserStatus {
    /** Registered a shop, has not entered the email verification code yet. */
    PENDING_VERIFICATION,
    ACTIVE,
    DISABLED
}

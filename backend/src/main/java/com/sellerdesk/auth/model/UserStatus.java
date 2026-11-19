package com.sellerdesk.auth.model;

/** Lifecycle of a user account (docs/SCHEMA.md §3). Only {@link #ACTIVE} users can log in. */
public enum UserStatus {
    /** Registered a shop, has not entered the email verification code yet. */
    PENDING_VERIFICATION,
    /** Staff created by the owner; has no password until they use the set/reset-password flow. */
    INVITED,
    ACTIVE,
    DISABLED
}

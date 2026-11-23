package com.sellerdesk.staff.service;

import com.sellerdesk.common.error.BusinessRuleException;
import com.sellerdesk.common.error.RateLimitedException;
import com.sellerdesk.common.error.ResourceNotFoundException;
import org.springframework.http.HttpStatus;

/**
 * Error codes of the staff module (docs/modules/STAFF.md §4) and factories for them.
 * The frontend switches on these codes.
 */
public final class StaffErrors {

    /** Member or invitation id unknown <b>or from another shop</b>: the two look the same. */
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String ALREADY_A_MEMBER = "ALREADY_A_MEMBER";
    public static final String EMAIL_ALREADY_REGISTERED = "EMAIL_ALREADY_REGISTERED";
    public static final String ALREADY_INVITED = "ALREADY_INVITED";
    public static final String INVITE_RESEND_TOO_SOON = "INVITE_RESEND_TOO_SOON";
    public static final String INVITE_RESEND_LIMIT = "INVITE_RESEND_LIMIT";
    public static final String INVITATION_NOT_PENDING = "INVITATION_NOT_PENDING";
    public static final String OWNER_READ_ONLY = "OWNER_READ_ONLY";
    public static final String CANNOT_MODIFY_SELF = "CANNOT_MODIFY_SELF";
    public static final String PERMISSION_NOT_GRANTABLE = "PERMISSION_NOT_GRANTABLE";
    public static final String INVALID_INVITATION = "INVALID_INVITATION";
    public static final String INVITATION_EXPIRED = "INVITATION_EXPIRED";

    private StaffErrors() {
    }

    public static ResourceNotFoundException memberNotFound() {
        return new ResourceNotFoundException(NOT_FOUND, "Staff member not found");
    }

    public static ResourceNotFoundException invitationNotFound() {
        return new ResourceNotFoundException(NOT_FOUND, "Invitation not found");
    }

    public static BusinessRuleException alreadyAMember() {
        return new BusinessRuleException(ALREADY_A_MEMBER, "This person is already a member of your shop");
    }

    public static BusinessRuleException emailAlreadyRegistered() {
        return new BusinessRuleException(EMAIL_ALREADY_REGISTERED, "An account with this email already exists");
    }

    public static BusinessRuleException alreadyInvited() {
        return new BusinessRuleException(ALREADY_INVITED,
                "This email already has an open invitation. Resend it instead");
    }

    public static RateLimitedException resendTooSoon(long retryAfterSeconds) {
        return new RateLimitedException(INVITE_RESEND_TOO_SOON,
                "Please wait a minute before sending this invitation again", retryAfterSeconds);
    }

    public static RateLimitedException resendLimit(long retryAfterSeconds) {
        return new RateLimitedException(INVITE_RESEND_LIMIT,
                "This invitation was sent too often today. Try again later", retryAfterSeconds);
    }

    public static BusinessRuleException invitationNotPending() {
        return new BusinessRuleException(INVITATION_NOT_PENDING, "This invitation was already accepted or cancelled");
    }

    public static BusinessRuleException ownerReadOnly() {
        return new BusinessRuleException(HttpStatus.FORBIDDEN, OWNER_READ_ONLY,
                "The shop owner's role, permissions and status cannot be changed");
    }

    public static BusinessRuleException cannotModifySelf() {
        return new BusinessRuleException(HttpStatus.FORBIDDEN, CANNOT_MODIFY_SELF,
                "You cannot change your own role, permissions or status");
    }

    public static BusinessRuleException permissionNotGrantable(String permissions) {
        return new BusinessRuleException(HttpStatus.FORBIDDEN, PERMISSION_NOT_GRANTABLE,
                "You can only grant permissions you have yourself (not: " + permissions + ")");
    }

    public static BusinessRuleException invalidInvitation() {
        return new BusinessRuleException(HttpStatus.BAD_REQUEST, INVALID_INVITATION,
                "This invitation link is invalid or was already used");
    }

    public static BusinessRuleException invitationExpired() {
        return new BusinessRuleException(HttpStatus.BAD_REQUEST, INVITATION_EXPIRED,
                "This invitation has expired. Ask the shop to send a new one");
    }
}

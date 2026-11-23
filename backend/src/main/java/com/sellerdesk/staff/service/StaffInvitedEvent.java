package com.sellerdesk.staff.service;

import com.sellerdesk.common.security.Role;
import java.time.Instant;

/**
 * "An invitation was created, reused or resent: email this link." Published inside the
 * transaction that saved the invitation; {@link StaffEmailListener} receives it only after the
 * commit succeeded.
 *
 * <p>The plain token lives only in this object and in the email. It is never stored or logged,
 * which is why {@link #toString()} hides it.
 */
public record StaffInvitedEvent(String invitationId, String email, String token, String shopName,
                                String inviterName, Role role, Instant expiresAt) {

    @Override
    public String toString() {
        return "StaffInvitedEvent[invitationId=" + invitationId + ", role=" + role + "]";
    }
}

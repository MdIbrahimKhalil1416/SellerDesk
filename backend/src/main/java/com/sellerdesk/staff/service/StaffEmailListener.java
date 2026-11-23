package com.sellerdesk.staff.service;

import com.sellerdesk.common.config.AppProperties;
import com.sellerdesk.common.mail.EmailSender;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the invitation email, like {@code AuthEmailListener} sends verification codes:
 * <ul>
 *   <li><b>after the commit</b>: if saving the invitation fails, no email goes out;</li>
 *   <li><b>on another thread</b> ({@code @Async}): the HTTP response does not wait for SMTP;</li>
 *   <li><b>with try/catch</b>: a mail failure is logged and the inviter can resend.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaffEmailListener {

    /** Bangladesh time, because the reader is a Bangladeshi seller's staff member. */
    private static final DateTimeFormatter EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("d MMMM yyyy, h:mm a", Locale.ENGLISH).withZone(ZoneId.of("Asia/Dhaka"));

    private final EmailSender emailSender;
    private final AppProperties appProperties;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStaffInvited(StaffInvitedEvent event) {
        try {
            emailSender.send(event.email(), subject(event), body(event, appProperties.appUrl()));
        } catch (RuntimeException e) {
            // Never log the token (it is in the link). The address is personal data: not logged either.
            log.warn("Could not send invitation email for invitation {}: {}", event.invitationId(), e.getMessage());
        }
    }

    static String subject(StaffInvitedEvent event) {
        return "%s invited you to %s on SellerDesk".formatted(event.inviterName(), event.shopName());
    }

    /**
     * The token is in the URL <b>fragment</b> ({@code #token=}): browsers never send it to the
     * server, so it stays out of access logs and {@code Referer} headers.
     */
    static String body(StaffInvitedEvent event, String appUrl) {
        return """
                Hello,

                %s invited you to join %s on SellerDesk as %s.

                Open this link to choose your name and password:
                %s/accept-invite#token=%s

                The link expires on %s (Bangladesh time).
                If you did not expect this invitation, you can ignore this email.

                - SellerDesk
                """.formatted(event.inviterName(), event.shopName(), roleLabel(event),
                appUrl, event.token(), EXPIRY_FORMAT.format(event.expiresAt()));
    }

    private static String roleLabel(StaffInvitedEvent event) {
        String name = event.role().name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}

package com.sellerdesk.auth.service;

import com.sellerdesk.auth.model.OtpPurpose;
import com.sellerdesk.common.mail.EmailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends auth emails in the background.
 *
 * <p>Why events: the code that registers a user should not know or care how email works, and a
 * broken mail server must not undo a registration. The listener runs:
 * <ul>
 *   <li><b>after the commit</b> ({@code @TransactionalEventListener(AFTER_COMMIT)}): if the
 *       registration transaction rolls back, no code is created and no email goes out;</li>
 *   <li><b>on another thread</b> ({@code @Async}): the HTTP response does not wait for SMTP;</li>
 *   <li><b>with try/catch</b>: a failure is logged, and the user can ask for a new code with
 *       {@code /auth/verify-email/resend}.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthEmailListener {

    private final OtpService otpService;
    private final AuthService authService;
    private final EmailSender emailSender;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserRegistered(UserRegisteredEvent event) {
        try {
            otpService.issue(event.email(), OtpPurpose.EMAIL_VERIFICATION, event.name());
        } catch (RuntimeException e) {
            log.warn("Could not create verification code for user {}: {}", event.userId(), e.getMessage());
        }
    }

    /** Resend / forgot password: decide in the background whether a code is sent at all. */
    @Async
    @EventListener
    public void onOtpRequested(OtpRequestedEvent event) {
        try {
            authService.sendCodeIfEligible(event.email(), event.purpose());
        } catch (RuntimeException e) {
            log.warn("Could not handle {} code request: {}", event.purpose(), e.getMessage());
        }
    }

    @Async
    @EventListener
    public void onOtpIssued(OtpIssuedEvent event) {
        try {
            emailSender.send(event.email(), subject(event.purpose()), body(event));
        } catch (RuntimeException e) {
            // Never log the code. The email address is personal data, so it is not logged either.
            log.warn("Could not send {} email: {}", event.purpose(), e.getMessage());
        }
    }

    private static String subject(OtpPurpose purpose) {
        return switch (purpose) {
            case EMAIL_VERIFICATION -> "Your SellerDesk verification code";
            case PASSWORD_RESET -> "Your SellerDesk password reset code";
        };
    }

    private static String body(OtpIssuedEvent event) {
        String name = event.recipientName() == null ? "there" : event.recipientName();
        return """
                Hello %s,

                Your SellerDesk code is: %s

                It expires in %d minutes. If you did not request it, you can ignore this email.

                - SellerDesk
                """.formatted(name, event.code(), OtpService.CODE_TTL.toMinutes());
    }
}

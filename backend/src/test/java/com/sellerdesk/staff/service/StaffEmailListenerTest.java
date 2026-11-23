package com.sellerdesk.staff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerdesk.common.config.AppProperties;
import com.sellerdesk.common.mail.EmailSender;
import com.sellerdesk.common.security.Role;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;

class StaffEmailListenerTest {

    private final EmailSender emailSender = mock(EmailSender.class);
    private final AppProperties appProperties = mock(AppProperties.class);
    private final StaffEmailListener listener = new StaffEmailListener(emailSender, appProperties);

    private final StaffInvitedEvent event = new StaffInvitedEvent("inv1", "karim@example.com", "tok_EN-123",
            "Rahim Fashion", "Rahim", Role.MODERATOR, Instant.parse("2026-11-27T10:00:00Z"));

    @Test
    void sendsLinkWithTokenInFragment() {
        when(appProperties.appUrl()).thenReturn("https://app.example");

        listener.onStaffInvited(event);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq("karim@example.com"), eq("Rahim invited you to Rahim Fashion on SellerDesk"), body.capture());
        assertThat(body.getValue())
                .contains("https://app.example/accept-invite#token=tok_EN-123")
                .contains("as Moderator")
                .contains("27 November 2026, 4:00 PM") // Asia/Dhaka
                .contains("ignore this email");
    }

    @Test
    void mailFailureIsSwallowed() {
        when(appProperties.appUrl()).thenReturn("https://app.example");
        doThrow(new MailSendException("down")).when(emailSender).send(any(), any(), any());

        listener.onStaffInvited(event); // no exception: the inviter can resend
    }

    @Test
    void eventToStringHidesToken() {
        assertThat(event.toString()).doesNotContain("tok_EN-123").doesNotContain("karim@");
    }
}

package com.sellerdesk.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.sellerdesk.auth.model.OtpPurpose;
import com.sellerdesk.common.mail.EmailSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;

class AuthEmailListenerTest {

    private final OtpService otpService = mock(OtpService.class);
    private final AuthService authService = mock(AuthService.class);
    private final EmailSender emailSender = mock(EmailSender.class);
    private final AuthEmailListener listener = new AuthEmailListener(otpService, authService, emailSender);

    @Test
    void codeRequestIsHandedToAuthServiceInTheBackground() {
        listener.onOtpRequested(new OtpRequestedEvent("rahim@example.com", OtpPurpose.PASSWORD_RESET));

        verify(authService).sendCodeIfEligible("rahim@example.com", OtpPurpose.PASSWORD_RESET);
    }

    @Test
    void codeRequestFailureIsSwallowed() {
        doThrow(new IllegalStateException("db down")).when(authService).sendCodeIfEligible(any(), any());

        assertThatCode(() -> listener.onOtpRequested(
                new OtpRequestedEvent("rahim@example.com", OtpPurpose.EMAIL_VERIFICATION)))
                .doesNotThrowAnyException();
    }

    @Test
    void resetEmailHasResetSubject() {
        listener.onOtpIssued(new OtpIssuedEvent("rahim@example.com", "Rahim", OtpPurpose.PASSWORD_RESET, "111222"));

        verify(emailSender).send(eq("rahim@example.com"), eq("Your SellerDesk password reset code"), anyString());
    }

    @Test
    void registrationIssuesVerificationCode() {
        listener.onUserRegistered(new UserRegisteredEvent("u1", "shopA", "rahim@example.com", "Rahim"));

        verify(otpService).issue("rahim@example.com", OtpPurpose.EMAIL_VERIFICATION, "Rahim");
    }

    @Test
    void issuedCodeIsEmailed() {
        listener.onOtpIssued(new OtpIssuedEvent("rahim@example.com", "Rahim", OtpPurpose.EMAIL_VERIFICATION, "042917"));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq("rahim@example.com"), eq("Your SellerDesk verification code"), body.capture());
        assertThat(body.getValue()).contains("042917").contains("Rahim").contains("10 minutes");
    }

    @Test
    void mailFailureIsSwallowedSoNothingUpstreamBreaks() {
        doThrow(new MailSendException("SMTP down")).when(emailSender).send(anyString(), anyString(), anyString());

        assertThatCode(() -> listener.onOtpIssued(
                new OtpIssuedEvent("rahim@example.com", "Rahim", OtpPurpose.EMAIL_VERIFICATION, "042917")))
                .doesNotThrowAnyException();
    }

    @Test
    void codeCreationFailureIsSwallowed() {
        doThrow(new IllegalStateException("db down")).when(otpService).issue(any(), any(), any());

        assertThatCode(() -> listener.onUserRegistered(
                new UserRegisteredEvent("u1", "shopA", "rahim@example.com", "Rahim")))
                .doesNotThrowAnyException();
    }
}

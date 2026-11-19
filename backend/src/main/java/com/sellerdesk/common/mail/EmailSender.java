package com.sellerdesk.common.mail;

import com.sellerdesk.common.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends plain-text emails through SMTP (Spring Mail). Feature code builds the text; this class
 * only knows how to deliver it.
 *
 * <p>Synchronous: it blocks until the SMTP server answers. Callers run it on a background thread
 * ({@code @Async} listeners) so HTTP requests never wait for, or fail because of, the mail server.
 */
@Component
@RequiredArgsConstructor
public class EmailSender {

    private final JavaMailSender mailSender;
    private final AppProperties appProperties;

    /** @throws MailException if the SMTP server rejects or cannot be reached */
    public void send(String to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(appProperties.mail().from());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        mailSender.send(message);
    }
}

package com.flowmail.delivery.provider;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "flowmail.mail.provider", havingValue = "smtp", matchIfMissing = true)
public class SmtpEmailProvider implements EmailProvider {

    private final JavaMailSender mailSender;

    public SmtpEmailProvider(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public boolean supportsIdempotencyKeys() {
        return false;
    }

    @Override
    public SendResult send(OutboundEmail email) {
        MimeMessage message = build(email);
        try {
            mailSender.send(message);
            return new SendResult("smtp-" + email.idempotencyKey());
        } catch (MailSendException e) {
            throw classify(e);
        } catch (MailException e) {
            throw new TransientSendException("SMTP error: " + e.getMessage());
        }
    }

    private MimeMessage build(OutboundEmail email) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(email.fromEmail(), email.fromName());
            helper.setTo(email.to());
            helper.setSubject(email.subject());
            if (email.textBody() != null && !email.textBody().isBlank()) {
                helper.setText(email.textBody(), email.htmlBody());
            } else {
                helper.setText(email.htmlBody(), true);
            }
            message.addHeader("List-Unsubscribe", "<" + email.unsubscribeUrl() + ">");
            message.addHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
            return message;
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new PermanentSendException("Message could not be built: " + e.getMessage());
        }
    }

    private static RuntimeException classify(MailSendException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException) {
                return new UncertainSendException("SMTP timeout: " + e.getMessage());
            }
            if (t instanceof SendFailedException failed
                    && failed.getInvalidAddresses() != null
                    && failed.getInvalidAddresses().length > 0) {
                return new PermanentSendException("Recipient rejected: " + e.getMessage());
            }
        }
        return new TransientSendException("SMTP send failed: " + e.getMessage());
    }
}
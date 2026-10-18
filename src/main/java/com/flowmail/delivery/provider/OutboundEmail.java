package com.flowmail.delivery.provider;

public record OutboundEmail(
        String idempotencyKey,
        String fromName,
        String fromEmail,
        String to,
        String subject,
        String htmlBody,
        String textBody,
        String unsubscribeUrl) {
}
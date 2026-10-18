package com.flowmail.delivery.provider;


public interface EmailProvider {

    /**
     * Sends one email. Throws PermanentSendException (never retry), TransientSendException (retry later)
     * or UncertainSendException (the provider may or may not have sent it).
     */
    SendResult send(OutboundEmail email);

    /**
     * True if sending the same idempotency key twice is guaranteed not to deliver twice.
     * When false, the worker never re-sends a message that was in flight when a worker died.
     */
    boolean supportsIdempotencyKeys();
}
package com.flowmail.delivery.provider;

public class TransientSendException extends RuntimeException {

    public TransientSendException(String message) {
        super(message);
    }
}
package com.flowmail.delivery.provider;

public class PermanentSendException extends RuntimeException {

    public PermanentSendException(String message) {
        super(message);
    }
}
package com.flowmail.delivery.provider;

public class UncertainSendException extends RuntimeException {

    public UncertainSendException(String message) {
        super(message);
    }
}
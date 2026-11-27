package com.innowise.orderservice.exception;

/**
 * The access token does not carry a claim required to execute the request
 * (for example, the {@code email} claim needed to create an order).
 */
public class MissingClaimException extends RuntimeException {

    public MissingClaimException(String message) {
        super(message);
    }
}

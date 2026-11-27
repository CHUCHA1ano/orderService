package com.innowise.orderservice.exception;

/**
 * Deleting a finalized (PAID/CANCELLED) order is a conflict with the current
 * resource state rather than a malformed request, so it maps to 409.
 */
public class OrderNotDeletableException extends RuntimeException {

    public OrderNotDeletableException(String message) {
        super(message);
    }
}

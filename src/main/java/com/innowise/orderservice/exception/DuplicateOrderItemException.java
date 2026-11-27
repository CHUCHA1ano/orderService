package com.innowise.orderservice.exception;

/**
 * Two positions in one order reference the same catalog item. Rejecting the
 * request keeps {@code (order_id, item_id)} unique and surfaces a client bug
 * instead of silently creating duplicate rows.
 */
public class DuplicateOrderItemException extends RuntimeException {

    public DuplicateOrderItemException(String message) {
        super(message);
    }
}

package com.innowise.orderservice.exception;

/**
 * The computed order total does not fit {@code orders.total_price NUMERIC(12,2)}.
 * Reporting it as a bad request avoids leaking a database constraint violation
 * as an internal server error.
 */
public class OrderTotalOverflowException extends RuntimeException {

    public OrderTotalOverflowException(String message) {
        super(message);
    }
}

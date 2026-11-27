package com.innowise.orderservice.exception;

/**
 * The acting user's profile exists but is deactivated. Mirrors the
 * {@code ActiveUserFilter} behaviour of userService: mutations are forbidden
 * with 403 while the account is inactive.
 */
public class InactiveUserException extends RuntimeException {

    public InactiveUserException(String message) {
        super(message);
    }
}

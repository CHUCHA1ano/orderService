package com.innowise.orderservice.integration.user.dto;

import java.util.UUID;

/**
 * Subset of the userService user profile embedded into order responses.
 */
public record UserDto(
        UUID id,
        String name,
        String surname,
        String email,
        Boolean active) {
}

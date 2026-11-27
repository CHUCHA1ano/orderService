package com.innowise.orderservice.integration.user;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Validated at startup: a missing URL or internal API key must fail fast instead
 * of producing a broken-call at request time.
 */
@Validated
@ConfigurationProperties(prefix = "app.integration.user-service")
public record UserServiceProperties(
        @NotBlank(message = "userService URL must be configured") String url,
        @NotBlank(message = "internal API key must be configured") String apiKey) {
}

package com.innowise.orderservice.integration.user;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserServicePropertiesTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void shouldPassForCompleteConfiguration() {
        UserServiceProperties properties =
                new UserServiceProperties("http://localhost:8081", "internal-key");

        assertTrue(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectBlankUrl() {
        var violations = validator.validate(new UserServiceProperties(" ", "internal-key"));

        assertEquals(1, violations.size());
        assertEquals("url", violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    void shouldRejectBlankApiKey() {
        var violations = validator.validate(new UserServiceProperties("http://localhost:8081", ""));

        assertEquals(1, violations.size());
        assertEquals("apiKey", violations.iterator().next().getPropertyPath().toString());
    }
}

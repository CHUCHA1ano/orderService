package com.innowise.orderservice.integration;

import com.innowise.orderservice.model.entity.Item;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The userService circuit breaker is a singleton shared by the whole Spring
 * context (and therefore by all IT classes), so its state is reset around every
 * test: failures accumulated elsewhere must not leak into this test and an open
 * circuit must not break the tests that run afterwards.
 */
class CircuitBreakerIntegrationTest extends AbstractOrderIntegrationTest {

    private static final String BREAKER_NAME = "userService";
    private static final String EMAIL = "breaker@example.com";
    private static final int MINIMUM_CALLS = 5;

    private final UUID userId = UUID.randomUUID();

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetBreaker() {
        circuitBreakerRegistry.circuitBreaker(BREAKER_NAME).reset();
    }

    @AfterEach
    void restoreBreaker() {
        circuitBreakerRegistry.circuitBreaker(BREAKER_NAME).reset();
    }

    @Test
    @DisplayName("An open circuit breaker returns 502 without calling userService")
    void openCircuitBreaker_shouldReturn502WithoutCallingUserService() throws Exception {
        Item item = createItem("Keyboard", "10.00");
        stubUserLookupError(500);

        for (int call = 0; call < MINIMUM_CALLS; call++) {
            expectCreateUnavailable(item.getId());
        }

        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(BREAKER_NAME);
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState(),
                "the configured failure threshold must open the circuit");

        // rejected by the breaker before the HTTP client runs: still 502, not 500
        expectCreateUnavailable(item.getId());

        WIREMOCK.verify(MINIMUM_CALLS,
                getRequestedFor(urlPathEqualTo("/api/internal/users/by-email")));
        assertEquals(0, jdbcTemplate.queryForObject("select count(*) from orders", Integer.class),
                "no order may be persisted when the profile lookup fails");
    }

    @Test
    @DisplayName("Resetting the circuit breaker restores normal calls")
    void resetBreaker_shouldAllowCallsAgain() throws Exception {
        Item item = createItem("Keyboard", "10.00");
        stubUserLookupError(500);

        for (int call = 0; call < MINIMUM_CALLS; call++) {
            expectCreateUnavailable(item.getId());
        }
        assertEquals(CircuitBreaker.State.OPEN,
                circuitBreakerRegistry.circuitBreaker(BREAKER_NAME).getState());

        circuitBreakerRegistry.circuitBreaker(BREAKER_NAME).reset();
        stubUserByEmail(EMAIL, userId);

        mockMvc.perform(post("/api/orders")
                        .with(userJwt(userId, EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"itemId":"%s","quantity":1}]}
                                """.formatted(item.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value(EMAIL));
    }

    private void expectCreateUnavailable(UUID itemId) throws Exception {
        mockMvc.perform(post("/api/orders")
                        .with(userJwt(userId, EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"itemId":"%s","quantity":1}]}
                                """.formatted(itemId)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("User service is temporarily unavailable"));
    }
}

package com.innowise.orderservice.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The actuator health endpoint is public (no JWT) and is what docker-compose uses
 * as a readiness check.
 */
class ActuatorHealthIntegrationTest extends AbstractOrderIntegrationTest {

    @Test
    @DisplayName("GET /actuator/health is public and reports UP")
    void health_shouldBePublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}

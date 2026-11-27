package com.innowise.orderservice.integration;

import com.innowise.orderservice.model.entity.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack order API tests: real Postgres (Testcontainers), real Liquibase
 * schema, real security (MockMvc jwt post-processor) and a real HTTP call to the
 * in-process WireMock that plays the role of userService.
 */
class OrderControllerIntegrationTest extends AbstractOrderIntegrationTest {

    private static final String EMAIL = "john@example.com";

    private final UUID userId = UUID.randomUUID();

    // --- create ---

    @Test
    @DisplayName("POST /api/orders computes the total from item prices and embeds user info")
    void create_shouldComputeTotalAndEmbedUser() throws Exception {
        Item itemA = createItem("Keyboard", "10.00");
        Item itemB = createItem("Mouse", "5.50");
        stubUserByEmail(EMAIL, userId);

        mockMvc.perform(post("/api/orders")
                        .with(userJwt(userId, EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"itemId":"%s","quantity":2},{"itemId":"%s","quantity":1}]}
                                """.formatted(itemA.getId(), itemB.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.status").value("NEW"))
                // 2 * 10.00 + 1 * 5.50, never taken from the request
                .andExpect(jsonPath("$.totalPrice").value(25.5))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.user.id").value(userId.toString()))
                .andExpect(jsonPath("$.user.email").value(EMAIL));

        WIREMOCK.verify(getRequestedFor(urlPathEqualTo("/api/internal/users/by-email"))
                .withQueryParam("email", equalTo(EMAIL))
                .withHeader("X-Internal-Api-Key", equalTo("test-internal-key")));
    }

    @Test
    @DisplayName("POST /api/orders without a token returns 401")
    void create_shouldRejectAnonymousRequest() throws Exception {
        Item item = createItem("Keyboard", "10.00");

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"itemId":"%s","quantity":1}]}
                                """.formatted(item.getId())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Authentication required"));
    }

    @Test
    @DisplayName("POST /api/orders with malformed JSON returns a generic 400 without Jackson details")
    void create_shouldReturnGenericMessageForMalformedJson() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .with(userJwt(userId, EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\": [not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Malformed request body"));
    }

    @Test
    @DisplayName("POST /api/orders with an empty items list returns 400")
    void create_shouldRejectEmptyItems() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .with(userJwt(userId, EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.items").exists());
    }

    @Test
    @DisplayName("POST /api/orders rejects a quantity above the configured maximum")
    void create_shouldRejectQuantityAboveMaximum() throws Exception {
        Item item = createItem("Keyboard", "10.00");

        mockMvc.perform(post("/api/orders")
                        .with(userJwt(userId, EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"itemId":"%s","quantity":1001}]}
                                """.formatted(item.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['items[0].quantity']").exists());

        assertEquals(0, countOrders());
    }

    @Test
    @DisplayName("POST /api/orders with duplicate item ids returns 400 and saves nothing")
    void create_shouldRejectDuplicateItemIds() throws Exception {
        Item item = createItem("Keyboard", "10.00");
        stubUserByEmail(EMAIL, userId);

        mockMvc.perform(post("/api/orders")
                        .with(userJwt(userId, EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"itemId":"%s","quantity":1},{"itemId":"%s","quantity":2}]}
                                """.formatted(item.getId(), item.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("Duplicate item ids")));

        assertEquals(0, countOrders());
    }

    @Test
    @DisplayName("POST /api/orders returns 400 when the total does not fit total_price")
    void create_shouldRejectTotalOverflow() throws Exception {
        Item expensive = itemRepository.save(Item.builder()
                .name("Expensive")
                .price(new BigDecimal("99999999.99"))
                .build());
        stubUserByEmail(EMAIL, userId);

        mockMvc.perform(post("/api/orders")
                        .with(userJwt(userId, EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"itemId":"%s","quantity":1000}]}
                                """.formatted(expensive.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("Order total must not exceed")));

        assertEquals(0, countOrders());
    }

    @Test
    @DisplayName("POST /api/orders maps a userService 404 to a local 404 and saves nothing")
    void create_shouldReturn404WhenUserDoesNotExist() throws Exception {
        Item item = createItem("Keyboard", "10.00");
        stubUserLookupError(404);

        mockMvc.perform(post("/api/orders")
                        .with(userJwt(userId, "ghost@example.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"itemId":"%s","quantity":1}]}
                                """.formatted(item.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("User not found: ghost@example.com"));

        assertEquals(0, countOrders());
    }

    @Test
    @DisplayName("POST /api/orders maps a userService 5xx to 502 and saves nothing")
    void create_shouldReturn502WhenUserServiceFails() throws Exception {
        Item item = createItem("Keyboard", "10.00");
        stubUserLookupError(500);

        mockMvc.perform(post("/api/orders")
                        .with(userJwt(userId, EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"itemId":"%s","quantity":1}]}
                                """.formatted(item.getId())))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("User service is temporarily unavailable"));

        assertEquals(0, countOrders());
    }

    @Test
    @DisplayName("POST /api/orders by an inactive user returns 403 and saves nothing")
    void create_shouldRejectInactiveUser() throws Exception {
        Item item = createItem("Keyboard", "10.00");
        stubUserByEmail(EMAIL, userId, false);

        mockMvc.perform(post("/api/orders")
                        .with(userJwt(userId, EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"itemId":"%s","quantity":1}]}
                                """.formatted(item.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("User is inactive"));

        assertEquals(0, countOrders());
    }

    // --- get by id ---

    @Test
    @DisplayName("GET /api/orders/{id} by the owner returns the enriched order")
    void getById_shouldAllowOwner() throws Exception {
        String orderId = givenOrderFor(userId, EMAIL, 2);
        stubUserById(userId, EMAIL);

        mockMvc.perform(get("/api/orders/{id}", orderId).with(userJwt(userId, EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.totalPrice").value(20.0))
                .andExpect(jsonPath("$.user.email").value(EMAIL));
    }

    @Test
    @DisplayName("GET /api/orders/{id} by another user returns 403")
    void getById_shouldForbidForeignOrder() throws Exception {
        String orderId = givenOrderFor(userId, EMAIL, 1);

        mockMvc.perform(get("/api/orders/{id}", orderId)
                        .with(userJwt(UUID.randomUUID(), "other@example.com")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Access denied"));
    }

    @Test
    @DisplayName("GET /api/orders/{id} as ADMIN returns any order")
    void getById_shouldAllowAdmin() throws Exception {
        String orderId = givenOrderFor(userId, EMAIL, 1);
        stubUserById(userId, EMAIL);

        mockMvc.perform(get("/api/orders/{id}", orderId).with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId));
    }

    @Test
    @DisplayName("GET /api/orders/{id} for an unknown id as ADMIN returns 404")
    void getById_shouldReturn404ForUnknownOrder() throws Exception {
        mockMvc.perform(get("/api/orders/{id}", UUID.randomUUID()).with(adminJwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/orders/{id} with a non-UUID id returns a generic 400")
    void getById_shouldReturnGenericMessageForInvalidId() throws Exception {
        mockMvc.perform(get("/api/orders/{id}", "not-a-uuid").with(adminJwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid request parameter"));
    }

    @Test
    @DisplayName("GET /api/orders/{id} of a soft-deleted order returns 404 to its owner")
    void getById_shouldReturn404ForDeletedOrderToOwner() throws Exception {
        String orderId = givenOrderFor(userId, EMAIL, 1);

        mockMvc.perform(delete("/api/orders/{id}", orderId).with(userJwt(userId, EMAIL)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/orders/{id}", orderId).with(userJwt(userId, EMAIL)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/orders/{id} of a soft-deleted order returns 404 to a foreign user")
    void getById_shouldReturn404ForDeletedOrderToForeignUser() throws Exception {
        String orderId = givenOrderFor(userId, EMAIL, 1);

        mockMvc.perform(delete("/api/orders/{id}", orderId).with(userJwt(userId, EMAIL)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/orders/{id}", orderId)
                        .with(userJwt(UUID.randomUUID(), "other@example.com")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/orders/{id} returns 200 with user=null when the profile is missing")
    void getById_shouldReturnNullUserWhenProfileIsMissing() throws Exception {
        String orderId = givenOrderFor(userId, EMAIL, 1);
        stubUserByIdError(404);

        mockMvc.perform(get("/api/orders/{id}", orderId).with(userJwt(userId, EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.user").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/orders/{id} still maps a userService outage to 502")
    void getById_shouldReturn502WhenUserServiceFails() throws Exception {
        String orderId = givenOrderFor(userId, EMAIL, 1);
        stubUserByIdError(500);

        mockMvc.perform(get("/api/orders/{id}", orderId).with(userJwt(userId, EMAIL)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("User service is temporarily unavailable"));
    }

    // --- admin list ---

    @Test
    @DisplayName("GET /api/orders applies status/date filters and enriches every row")
    void getAll_shouldApplyFilters() throws Exception {
        Item item = createItem("Keyboard", "10.00");
        stubUserByEmail(EMAIL, userId);
        String newOrderId = createOrder(userJwt(userId, EMAIL), item.getId(), 1);
        String paidOrderId = createOrder(userJwt(userId, EMAIL), item.getId(), 2);
        stubUserById(userId, EMAIL);

        mockMvc.perform(patch("/api/orders/{id}", paidOrderId)
                        .with(userJwt(userId, EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAID\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/orders")
                        .param("status", "NEW")
                        .param("size", "10")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(newOrderId))
                .andExpect(jsonPath("$.content[0].user.email").value(EMAIL))
                .andExpect(jsonPath("$.content[0].items", hasSize(1)))
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/orders")
                        .param("createdFrom", OffsetDateTime.now().minusDays(1).toString())
                        .param("size", "10")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("GET /api/orders returns user=null for an order whose profile is missing")
    void getAll_shouldReturnNullUserWhenProfileIsMissing() throws Exception {
        String orderId = givenOrderFor(userId, EMAIL, 1);
        stubUserByIdError(404);

        mockMvc.perform(get("/api/orders")
                        .param("size", "10")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(orderId))
                .andExpect(jsonPath("$.content[0].user").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/orders as a regular user returns 403")
    void getAll_shouldForbidRegularUser() throws Exception {
        mockMvc.perform(get("/api/orders").with(userJwt(userId, EMAIL)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Access denied"));
    }

    // --- orders by user ---

    @Test
    @DisplayName("GET /api/orders/by-user/{userId} returns own orders with a single user lookup")
    void getAllByUserId_shouldAllowSelf() throws Exception {
        Item item = createItem("Keyboard", "10.00");
        stubUserByEmail(EMAIL, userId);
        createOrder(userJwt(userId, EMAIL), item.getId(), 1);
        createOrder(userJwt(userId, EMAIL), item.getId(), 2);
        stubUserById(userId, EMAIL);

        mockMvc.perform(get("/api/orders/by-user/{userId}", userId)
                        .with(userJwt(userId, EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].userId").value(userId.toString()))
                .andExpect(jsonPath("$.content[0].user.email").value(EMAIL))
                .andExpect(jsonPath("$.content[0].items", hasSize(1)))
                .andExpect(jsonPath("$.totalElements").value(2));

        // one profile per request, not per order
        WIREMOCK.verify(1, getRequestedFor(urlPathEqualTo("/api/internal/users/" + userId)));
    }

    @Test
    @DisplayName("GET /api/orders/by-user/{userId} for another user returns 403")
    void getAllByUserId_shouldForbidForeignUser() throws Exception {
        mockMvc.perform(get("/api/orders/by-user/{userId}", userId)
                        .with(userJwt(UUID.randomUUID(), "other@example.com")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/orders/by-user/{userId} as ADMIN is allowed")
    void getAllByUserId_shouldAllowAdmin() throws Exception {
        String orderId = givenOrderFor(userId, EMAIL, 1);
        stubUserById(userId, EMAIL);

        mockMvc.perform(get("/api/orders/by-user/{userId}", userId).with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(orderId));
    }

    // --- update ---

    @Test
    @DisplayName("PATCH /api/orders/{id} moves NEW -> PAID and rejects a second change")
    void update_shouldChangeStatusOfNewOrderOnly() throws Exception {
        String orderId = givenOrderFor(userId, EMAIL, 2);
        stubUserById(userId, EMAIL);

        mockMvc.perform(patch("/api/orders/{id}", orderId)
                        .with(userJwt(userId, EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.totalPrice").value(20.0));

        assertEquals(1L, jdbcTemplate.queryForObject(
                "select version from orders where id = ?", Long.class, UUID.fromString(orderId)));

        mockMvc.perform(patch("/api/orders/{id}", orderId)
                        .with(userJwt(userId, EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("PATCH /api/orders/{id} by another user returns 403")
    void update_shouldForbidForeignOrder() throws Exception {
        String orderId = givenOrderFor(userId, EMAIL, 1);

        mockMvc.perform(patch("/api/orders/{id}", orderId)
                        .with(userJwt(UUID.randomUUID(), "other@example.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAID\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /api/orders/{id} returns 200 with user=null when the profile is missing")
    void update_shouldReturnNullUserWhenProfileIsMissing() throws Exception {
        String orderId = givenOrderFor(userId, EMAIL, 1);
        stubUserByIdError(404);

        mockMvc.perform(patch("/api/orders/{id}", orderId)
                        .with(userJwt(userId, EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.user").doesNotExist());
    }

    @Test
    @DisplayName("PATCH /api/orders/{id} by an inactive owner returns 403 and keeps the status")
    void update_shouldRejectInactiveOwner() throws Exception {
        String orderId = givenOrderFor(userId, EMAIL, 1);
        stubUserById(userId, EMAIL, false);

        mockMvc.perform(patch("/api/orders/{id}", orderId)
                        .with(userJwt(userId, EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAID\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("User is inactive"));

        assertEquals("NEW", jdbcTemplate.queryForObject(
                "select status from orders where id = ?", String.class, UUID.fromString(orderId)));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "select version from orders where id = ?", Long.class, UUID.fromString(orderId)));
    }

    @Test
    @DisplayName("PATCH /api/orders/{id} as ADMIN is allowed for an inactive owner")
    void update_shouldAllowAdminForInactiveOwner() throws Exception {
        String orderId = givenOrderFor(userId, EMAIL, 1);
        stubUserById(userId, EMAIL, false);

        mockMvc.perform(patch("/api/orders/{id}", orderId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.user.active").value(false));
    }

    @Test
    @DisplayName("PATCH /api/orders/{id} loads the owner profile exactly once")
    void update_shouldLoadOwnerProfileOnce() throws Exception {
        String orderId = givenOrderFor(userId, EMAIL, 1);
        stubUserById(userId, EMAIL);

        mockMvc.perform(patch("/api/orders/{id}", orderId)
                        .with(userJwt(userId, EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(EMAIL));

        WIREMOCK.verify(1, getRequestedFor(urlPathEqualTo("/api/internal/users/" + userId)));
    }

    // --- delete ---

    @Test
    @DisplayName("DELETE /api/orders/{id} soft-deletes the row and hides it from queries")
    void delete_shouldSoftDeleteOrder() throws Exception {
        String orderId = givenOrderFor(userId, EMAIL, 1);

        mockMvc.perform(delete("/api/orders/{id}", orderId).with(userJwt(userId, EMAIL)))
                .andExpect(status().isNoContent());

        Boolean deleted = jdbcTemplate.queryForObject(
                "select deleted from orders where id = ?", Boolean.class, UUID.fromString(orderId));
        assertTrue(Boolean.TRUE.equals(deleted), "row must stay in the table with deleted = true");
        assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from orders where id = ?", Integer.class, UUID.fromString(orderId)));
        assertEquals(1L, jdbcTemplate.queryForObject(
                "select version from orders where id = ?", Long.class, UUID.fromString(orderId)));

        mockMvc.perform(get("/api/orders/{id}", orderId).with(adminJwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/orders/{id} does not require an active profile")
    void delete_shouldNotCheckActiveFlag() throws Exception {
        String orderId = givenOrderFor(userId, EMAIL, 1);
        // no by-id stub: any profile lookup during delete would fail loudly
        stubUserByIdError(500);

        mockMvc.perform(delete("/api/orders/{id}", orderId).with(userJwt(userId, EMAIL)))
                .andExpect(status().isNoContent());

        WIREMOCK.verify(0, getRequestedFor(urlPathEqualTo("/api/internal/users/" + userId)));
    }

    @Test
    @DisplayName("DELETE /api/orders/{id} of a PAID order returns 409 and keeps the row")
    void delete_shouldRejectFinalizedOrder() throws Exception {
        String orderId = givenOrderFor(userId, EMAIL, 1);
        stubUserById(userId, EMAIL);

        mockMvc.perform(patch("/api/orders/{id}", orderId)
                        .with(userJwt(userId, EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAID\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/orders/{id}", orderId).with(userJwt(userId, EMAIL)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Order in status PAID cannot be deleted"));

        Boolean deleted = jdbcTemplate.queryForObject(
                "select deleted from orders where id = ?", Boolean.class, UUID.fromString(orderId));
        assertEquals(Boolean.FALSE, deleted, "a rejected delete must not touch the row");

        mockMvc.perform(get("/api/orders/{id}", orderId).with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    private String givenOrderFor(UUID ownerId, String email, int quantity) throws Exception {
        Item item = createItem("Keyboard", "10.00");
        stubUserByEmail(email, ownerId);
        return createOrder(userJwt(ownerId, email), item.getId(), quantity);
    }

    private int countOrders() {
        return jdbcTemplate.queryForObject("select count(*) from orders", Integer.class);
    }
}

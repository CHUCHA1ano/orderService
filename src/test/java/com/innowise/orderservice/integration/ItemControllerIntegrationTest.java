package com.innowise.orderservice.integration;

import com.innowise.orderservice.model.entity.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Item catalog API: reads are open to any authenticated user, mutations are
 * ADMIN-only, and an item referenced by an order cannot be removed (FK RESTRICT
 * surfaced as a readable 409).
 */
class ItemControllerIntegrationTest extends AbstractOrderIntegrationTest {

    private static final String EMAIL = "john@example.com";

    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("POST /api/items as ADMIN creates an item")
    void create_shouldAllowAdmin() throws Exception {
        mockMvc.perform(post("/api/items")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Keyboard\",\"price\":49.99}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Keyboard"))
                .andExpect(jsonPath("$.price").value(49.99));
    }

    @Test
    @DisplayName("POST /api/items as a regular user returns 403")
    void create_shouldForbidRegularUser() throws Exception {
        mockMvc.perform(post("/api/items")
                        .with(userJwt(userId, EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Keyboard\",\"price\":49.99}"))
                .andExpect(status().isForbidden());

        assertEquals(0, itemRepository.count());
    }

    @Test
    @DisplayName("POST /api/items with invalid data returns 400 with field errors")
    void create_shouldValidateBody() throws Exception {
        mockMvc.perform(post("/api/items")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \",\"price\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.price").exists());
    }

    @Test
    @DisplayName("POST /api/items rejects a price that does not fit NUMERIC(10,2)")
    void create_shouldRejectPriceWithTooManyIntegerDigits() throws Exception {
        mockMvc.perform(post("/api/items")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Keyboard\",\"price\":100000000.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price").exists());

        assertEquals(0, itemRepository.count());
    }

    @Test
    @DisplayName("GET /api/items/{id} and GET /api/items are available to any user")
    void read_shouldAllowRegularUser() throws Exception {
        Item item = createItem("Keyboard", "49.99");

        mockMvc.perform(get("/api/items/{id}", item.getId()).with(userJwt(userId, EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Keyboard"));

        mockMvc.perform(get("/api/items").with(userJwt(userId, EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    @DisplayName("GET /api/items/{id} for an unknown id returns 404")
    void getById_shouldReturn404ForUnknownItem() throws Exception {
        mockMvc.perform(get("/api/items/{id}", UUID.randomUUID()).with(userJwt(userId, EMAIL)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/items/{id} as ADMIN applies a partial update")
    void update_shouldChangeOnlyProvidedFields() throws Exception {
        Item item = createItem("Keyboard", "49.99");

        mockMvc.perform(put("/api/items/{id}", item.getId())
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mechanical Keyboard\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(item.getId().toString()))
                .andExpect(jsonPath("$.name").value("Mechanical Keyboard"))
                // price is null in the DTO -> keep the stored value
                .andExpect(jsonPath("$.price").value(49.99));
    }

    @Test
    @DisplayName("PUT /api/items/{id} as a regular user returns 403")
    void update_shouldForbidRegularUser() throws Exception {
        Item item = createItem("Keyboard", "49.99");

        mockMvc.perform(put("/api/items/{id}", item.getId())
                        .with(userJwt(userId, EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hacked\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/items/{id} removes an unreferenced item")
    void delete_shouldRemoveUnreferencedItem() throws Exception {
        Item item = createItem("Keyboard", "49.99");

        mockMvc.perform(delete("/api/items/{id}", item.getId()).with(adminJwt()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/items/{id}", item.getId()).with(adminJwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/items/{id} referenced by an order returns 409")
    void delete_shouldRejectItemReferencedByOrder() throws Exception {
        Item item = createItem("Keyboard", "49.99");
        stubUserByEmail(EMAIL, userId);
        createOrder(userJwt(userId, EMAIL), item.getId(), 1);

        mockMvc.perform(delete("/api/items/{id}", item.getId()).with(adminJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());

        assertTrue(itemRepository.existsById(item.getId()), "item must stay in the catalog");
    }

    @Test
    @DisplayName("DELETE /api/items/{id} succeeds when the only reference is a soft-deleted order")
    void delete_shouldAllowItemReferencedOnlyByDeletedOrder() throws Exception {
        Item item = createItem("Keyboard", "49.99");
        stubUserByEmail(EMAIL, userId);
        String orderId = createOrder(userJwt(userId, EMAIL), item.getId(), 1);

        mockMvc.perform(delete("/api/orders/{id}", orderId).with(userJwt(userId, EMAIL)))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/items/{id}", item.getId()).with(adminJwt()))
                .andExpect(status().isNoContent());

        assertFalse(itemRepository.existsById(item.getId()), "a deleted order must not pin the item");
    }

    @Test
    @DisplayName("DELETE /api/items/{id} as a regular user returns 403")
    void delete_shouldForbidRegularUser() throws Exception {
        Item item = createItem("Keyboard", "49.99");

        mockMvc.perform(delete("/api/items/{id}", item.getId()).with(userJwt(userId, EMAIL)))
                .andExpect(status().isForbidden());
    }
}

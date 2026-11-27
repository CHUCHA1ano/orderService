package com.innowise.orderservice.model.dto;

import com.innowise.orderservice.model.entity.OrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Partial update: null fields keep their current values.
 * A non-null items list replaces the whole set of order items.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrderUpdateDto {

    private OrderStatus status;

    /**
     * {@code @Size} instead of {@code @NotEmpty}: a null list means "keep the
     * current items", while an explicitly empty list is rejected.
     */
    @Size(min = 1, message = "Order must contain at least one item")
    private List<@Valid OrderItemCreateDto> items;
}

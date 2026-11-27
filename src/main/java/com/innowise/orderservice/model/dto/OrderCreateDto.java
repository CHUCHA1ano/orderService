package com.innowise.orderservice.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The order total is calculated by the server from actual item prices,
 * so it is deliberately absent from the request.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrderCreateDto {

    @NotEmpty(message = "Order must contain at least one item")
    private List<@Valid OrderItemCreateDto> items;
}

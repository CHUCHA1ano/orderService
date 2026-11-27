package com.innowise.orderservice.model.dto;

import com.innowise.orderservice.integration.user.dto.UserDto;
import com.innowise.orderservice.model.entity.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrderResponseDto {

    private UUID id;
    private UUID userId;
    private OrderStatus status;
    private BigDecimal totalPrice;
    private List<OrderItemResponseDto> items;

    /**
     * Null when the profile no longer exists in userService; the order itself
     * remains readable.
     */
    @Schema(nullable = true)
    private UserDto user;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

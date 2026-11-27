package com.innowise.orderservice.model.dto;

import com.innowise.orderservice.model.validation.NotBlankOrNull;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Partial update: null fields keep their current values.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ItemUpdateDto {

    @NotBlankOrNull(message = "Name cannot be empty")
    private String name;

    @DecimalMin(value = "0.01", message = "Price must be positive")
    @Digits(integer = 8, fraction = 2,
            message = "Price must have at most 8 integer and 2 fractional digits")
    private BigDecimal price;
}

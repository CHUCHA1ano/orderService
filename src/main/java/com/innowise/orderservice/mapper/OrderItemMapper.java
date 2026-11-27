package com.innowise.orderservice.mapper;

import com.innowise.orderservice.model.dto.OrderItemResponseDto;
import com.innowise.orderservice.model.entity.OrderItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface OrderItemMapper {

    @Mapping(target = "itemId", source = "item.id")
    OrderItemResponseDto toResponse(OrderItem entity);
}

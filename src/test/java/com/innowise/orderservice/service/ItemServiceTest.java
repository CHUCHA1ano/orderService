package com.innowise.orderservice.service;

import com.innowise.orderservice.exception.ItemInUseException;
import com.innowise.orderservice.exception.ItemNotFoundException;
import com.innowise.orderservice.mapper.ItemMapper;
import com.innowise.orderservice.model.dto.ItemCreateDto;
import com.innowise.orderservice.model.dto.ItemResponseDto;
import com.innowise.orderservice.model.dto.ItemUpdateDto;
import com.innowise.orderservice.model.entity.Item;
import com.innowise.orderservice.repository.ItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemServiceTest {

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private ItemMapper itemMapper;

    @InjectMocks
    private ItemService itemService;

    private UUID itemId;
    private Item item;
    private ItemResponseDto responseDto;

    @BeforeEach
    void setUp() {
        itemId = UUID.randomUUID();
        item = Item.builder().id(itemId).name("Widget").price(new BigDecimal("9.99")).build();
        responseDto = ItemResponseDto.builder()
                .id(itemId)
                .name("Widget")
                .price(new BigDecimal("9.99"))
                .build();
    }

    @Test
    void create_shouldReturnSavedItem() {
        ItemCreateDto dto = ItemCreateDto.builder().name("Widget").price(new BigDecimal("9.99")).build();

        when(itemMapper.toEntity(dto)).thenReturn(item);
        when(itemRepository.save(item)).thenReturn(item);
        when(itemMapper.toResponse(item)).thenReturn(responseDto);

        ItemResponseDto result = itemService.create(dto);

        assertSame(responseDto, result);
        verify(itemRepository).save(item);
    }

    @Test
    void getById_shouldReturnItem() {
        when(itemRepository.findById(itemId)).thenReturn(Optional.of(item));
        when(itemMapper.toResponse(item)).thenReturn(responseDto);

        assertSame(responseDto, itemService.getById(itemId));
    }

    @Test
    void getById_shouldThrowItemNotFoundException() {
        when(itemRepository.findById(itemId)).thenReturn(Optional.empty());

        assertThrows(ItemNotFoundException.class, () -> itemService.getById(itemId));
    }

    @Test
    void getAll_shouldMapPage() {
        Pageable pageable = PageRequest.of(0, 10);
        when(itemRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(item), pageable, 1));
        when(itemMapper.toResponse(item)).thenReturn(responseDto);

        Page<ItemResponseDto> page = itemService.getAll(pageable);

        assertEquals(1, page.getTotalElements());
        assertSame(responseDto, page.getContent().get(0));
    }

    @Test
    void update_shouldApplyPartialChanges() {
        ItemUpdateDto dto = ItemUpdateDto.builder().name("Widget Pro").build();

        when(itemRepository.findById(itemId)).thenReturn(Optional.of(item));
        when(itemRepository.save(item)).thenReturn(item);
        when(itemMapper.toResponse(item)).thenReturn(responseDto);

        ItemResponseDto result = itemService.update(itemId, dto);

        assertSame(responseDto, result);
        verify(itemMapper).updateEntity(dto, item);
        verify(itemRepository).save(item);
    }

    @Test
    void update_shouldThrowItemNotFoundException() {
        ItemUpdateDto dto = ItemUpdateDto.builder().name("Widget Pro").build();
        when(itemRepository.findById(itemId)).thenReturn(Optional.empty());

        assertThrows(ItemNotFoundException.class, () -> itemService.update(itemId, dto));
    }

    @Test
    void delete_shouldDeleteUnreferencedItem() {
        when(itemRepository.findById(itemId)).thenReturn(Optional.of(item));
        when(itemRepository.existsOrderItemByItemId(itemId)).thenReturn(false);

        itemService.delete(itemId);

        verify(itemRepository).deleteItemsFromDeletedOrders(itemId);
        verify(itemRepository).delete(item);
    }

    @Test
    void delete_shouldRejectItemReferencedByOrders() {
        when(itemRepository.findById(itemId)).thenReturn(Optional.of(item));
        when(itemRepository.existsOrderItemByItemId(itemId)).thenReturn(true);

        assertThrows(ItemInUseException.class, () -> itemService.delete(itemId));
        verify(itemRepository, never()).deleteItemsFromDeletedOrders(any());
        verify(itemRepository, never()).delete(any(Item.class));
    }

    @Test
    void delete_shouldThrowItemNotFoundException() {
        when(itemRepository.findById(itemId)).thenReturn(Optional.empty());

        assertThrows(ItemNotFoundException.class, () -> itemService.delete(itemId));
        verify(itemRepository, never()).delete(any(Item.class));
    }
}

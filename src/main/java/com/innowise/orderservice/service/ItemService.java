package com.innowise.orderservice.service;

import com.innowise.orderservice.exception.ItemInUseException;
import com.innowise.orderservice.exception.ItemNotFoundException;
import com.innowise.orderservice.mapper.ItemMapper;
import com.innowise.orderservice.model.dto.ItemCreateDto;
import com.innowise.orderservice.model.dto.ItemResponseDto;
import com.innowise.orderservice.model.dto.ItemUpdateDto;
import com.innowise.orderservice.model.entity.Item;
import com.innowise.orderservice.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Item catalog management. No external calls here, so plain @Transactional
 * is enough (mentor rule: external calls are the only reason for
 * TransactionTemplate orchestration).
 */
@Service
@RequiredArgsConstructor
public class ItemService {

    private final ItemRepository itemRepository;
    private final ItemMapper itemMapper;

    @Transactional
    public ItemResponseDto create(ItemCreateDto dto) {
        return itemMapper.toResponse(itemRepository.save(itemMapper.toEntity(dto)));
    }

    @Transactional(readOnly = true)
    public ItemResponseDto getById(UUID id) {
        return itemMapper.toResponse(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<ItemResponseDto> getAll(Pageable pageable) {
        return itemRepository.findAll(pageable).map(itemMapper::toResponse);
    }

    @Transactional
    public ItemResponseDto update(UUID id, ItemUpdateDto dto) {
        Item item = findOrThrow(id);
        itemMapper.updateEntity(dto, item);
        return itemMapper.toResponse(itemRepository.save(item));
    }

    @Transactional
    public void delete(UUID id) {
        Item item = findOrThrow(id);
        if (itemRepository.existsOrderItemByItemId(id)) {
            throw new ItemInUseException("Item is referenced by orders and cannot be deleted: " + id);
        }
        // Active orders are rejected above; positions of soft-deleted orders
        // still hold a RESTRICT FK and must be removed first.
        itemRepository.deleteItemsFromDeletedOrders(id);
        itemRepository.delete(item);
    }

    private Item findOrThrow(UUID id) {
        return itemRepository.findById(id)
                .orElseThrow(() -> new ItemNotFoundException("Item not found: " + id));
    }
}

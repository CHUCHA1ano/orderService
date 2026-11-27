package com.innowise.orderservice.service;

import com.innowise.orderservice.exception.DuplicateOrderItemException;
import com.innowise.orderservice.exception.InvalidOrderStateException;
import com.innowise.orderservice.exception.ItemNotFoundException;
import com.innowise.orderservice.exception.InactiveUserException;
import com.innowise.orderservice.exception.OrderNotDeletableException;
import com.innowise.orderservice.exception.OrderNotFoundException;
import com.innowise.orderservice.exception.OrderTotalOverflowException;
import com.innowise.orderservice.exception.UserNotFoundException;
import com.innowise.orderservice.integration.user.UserServiceClient;
import com.innowise.orderservice.integration.user.dto.UserDto;
import com.innowise.orderservice.mapper.OrderMapper;
import com.innowise.orderservice.model.dto.OrderCreateDto;
import com.innowise.orderservice.model.dto.OrderItemCreateDto;
import com.innowise.orderservice.model.dto.OrderResponseDto;
import com.innowise.orderservice.model.dto.OrderUpdateDto;
import com.innowise.orderservice.model.entity.Item;
import com.innowise.orderservice.model.entity.Order;
import com.innowise.orderservice.model.entity.OrderItem;
import com.innowise.orderservice.model.entity.OrderStatus;
import com.innowise.orderservice.repository.ItemRepository;
import com.innowise.orderservice.repository.OrderRepository;
import com.innowise.orderservice.repository.spec.OrderSpecifications;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderService {

    /**
     * {@code orders.total_price} is {@code NUMERIC(12,2)}, so anything above this
     * value would be rejected by the database as a 500; it is a bad request.
     */
    private static final BigDecimal MAX_TOTAL_PRICE = new BigDecimal("9999999999.99");

    private final OrderRepository orderRepository;
    private final ItemRepository itemRepository;
    private final UserServiceClient userServiceClient;
    private final OrderMapper orderMapper;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;

    /**
     * The user lookup (external HTTP call) happens BEFORE any transaction opens,
     * and the local writes run in a short TransactionTemplate block (mentor rule:
     * external calls are never inside DB transactions). {@code cascade = ALL}
     * persists the order and its positions in one save; the total is computed
     * from actual item prices, never from client input.
     */
    public OrderResponseDto create(String userEmail, OrderCreateDto dto) {
        UserDto user = userServiceClient.getUserByEmail(userEmail);
        requireActive(user);
        Order saved = transactionTemplate.execute(status -> {
            Map<UUID, Item> itemsById = resolveItems(dto.getItems());
            Order order = orderMapper.toEntity(dto);
            order.setUserId(user.id());
            order.setStatus(OrderStatus.NEW);
            order.setTotalPrice(BigDecimal.ZERO);
            addItems(order, dto.getItems(), itemsById);
            order.setTotalPrice(calculateTotal(order.getItems()));
            return orderRepository.save(order);
        });
        return orderMapper.toResponse(saved, user);
    }

    /**
     * Read + external enrichment: the repository call runs in its own implicit
     * read-only transaction, the userService call stays outside any transaction.
     */
    public OrderResponseDto getById(UUID id) {
        Order order = findWithItemsOrThrow(id);
        return orderMapper.toResponse(order, findUserOrNull(order.getUserId()));
    }

    /**
     * Paginated search with optional creation-date range and status filters;
     * all filters are optional and combined with AND. Items are loaded for the
     * whole page in one query (no N+1, no lazy access outside a transaction);
     * user enrichment still costs one userService call per order until the
     * batch endpoint exists.
     */
    public Page<OrderResponseDto> getAll(OffsetDateTime createdFrom, OffsetDateTime createdTo,
                                         List<OrderStatus> statuses, Pageable pageable) {
        Specification<Order> spec = Specification.allOf();
        if (createdFrom != null || createdTo != null) {
            spec = spec.and(OrderSpecifications.createdBetween(createdFrom, createdTo));
        }
        if (statuses != null && !statuses.isEmpty()) {
            spec = spec.and(OrderSpecifications.hasStatusIn(statuses));
        }
        Page<Order> page = orderRepository.findAll(spec, pageable);
        List<UUID> ids = page.getContent().stream().map(Order::getId).toList();
        Map<UUID, Order> ordersById = loadWithItems(ids);
        List<OrderResponseDto> content = ids.stream()
                .map(ordersById::get)
                .filter(Objects::nonNull)
                .map(order -> orderMapper.toResponse(order, findUserOrNull(order.getUserId())))
                .toList();
        return new PageImpl<>(content, pageable, page.getTotalElements());
    }

    /**
     * All orders of one user share the same profile, so userService is called
     * once per request, not per order. Items are loaded for the whole page in
     * one query, mirroring {@link #getAll}.
     */
    public Page<OrderResponseDto> getAllByUserId(UUID userId, Pageable pageable) {
        Page<Order> page = orderRepository.findAll(
                OrderSpecifications.belongsToUser(userId), pageable);
        List<UUID> ids = page.getContent().stream().map(Order::getId).toList();
        if (ids.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, page.getTotalElements());
        }
        UserDto user = findUserOrNull(userId);
        Map<UUID, Order> ordersById = loadWithItems(ids);
        List<OrderResponseDto> content = ids.stream()
                .map(ordersById::get)
                .filter(Objects::nonNull)
                .map(order -> orderMapper.toResponse(order, user))
                .toList();
        return new PageImpl<>(content, pageable, page.getTotalElements());
    }

    /**
     * Partial update: null DTO fields keep their values; a non-null items list
     * replaces the whole set via orphanRemoval. Finalized orders (PAID/CANCELLED)
     * cannot be modified. A non-admin actor must be active, and the profile is
     * loaded once before the transaction and reused in the response. ADMINS skip
     * the active check (same rule as {@code ActiveUserFilter} in userService).
     */
    public OrderResponseDto update(UUID id, OrderUpdateDto dto, boolean admin) {
        UserDto profile = admin ? null : requireActiveOwner(id);
        Order saved = transactionTemplate.execute(status -> {
            Order order = findWithItemsOrThrow(id);
            validateUpdate(order, dto);
            orderMapper.updateEntity(dto, order);
            if (dto.getItems() != null) {
                Map<UUID, Item> itemsById = resolveItems(dto.getItems());
                order.getItems().clear();
                addItems(order, dto.getItems(), itemsById);
                order.setTotalPrice(calculateTotal(order.getItems()));
            }
            Order updated = orderRepository.save(order);
            if (dto.getItems() != null) {
                // An items-only update may not touch the orders row; force a
                // version bump so a concurrent update cannot be silently lost.
                entityManager.lock(updated, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
            }
            return updated;
        });
        UserDto responseUser = admin ? findUserOrNull(saved.getUserId()) : profile;
        return orderMapper.toResponse(saved, responseUser);
    }

    /**
     * Soft delete: the row stays in the database with {@code deleted = true};
     * {@code @SQLRestriction} makes it invisible to every subsequent query.
     * Only NEW orders may be deleted — PAID/CANCELLED orders are immutable, so
     * their deletion is a state conflict (409), not a successful operation.
     */
    public void delete(UUID id) {
        Integer deleted = transactionTemplate.execute(status -> {
            Order order = orderRepository.findById(id)
                    .orElseThrow(() -> new OrderNotFoundException("Order not found: " + id));
            if (order.getStatus() != OrderStatus.NEW) {
                throw new OrderNotDeletableException(
                        "Order in status " + order.getStatus() + " cannot be deleted");
            }
            return orderRepository.softDelete(id);
        });
        if (deleted == null || deleted == 0) {
            throw new OrderNotFoundException("Order not found: " + id);
        }
    }

    private Order findWithItemsOrThrow(UUID id) {
        return orderRepository.findByIdWithItems(id)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + id));
    }

    /**
     * A missing profile is not an error for reads and updates: the order still
     * exists, so the response carries {@code user: null}. A userService outage
     * (5xx/timeout) is still reported as 502.
     */
    private UserDto findUserOrNull(UUID userId) {
        try {
            return userServiceClient.getUserById(userId);
        } catch (UserNotFoundException ex) {
            return null;
        }
    }

    /**
     * The owner id is read before the transaction so an inactive user is
     * rejected before any row is touched. A missing profile cannot prove the
     * user is active, but reads/updates remain allowed with {@code user: null}.
     */
    private UserDto requireActiveOwner(UUID orderId) {
        UUID ownerId = orderRepository.findUserIdById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
        UserDto profile = findUserOrNull(ownerId);
        if (profile != null) {
            requireActive(profile);
        }
        return profile;
    }

    private static void requireActive(UserDto user) {
        if (!Boolean.TRUE.equals(user.active())) {
            throw new InactiveUserException("User is inactive");
        }
    }

    private Map<UUID, Order> loadWithItems(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return orderRepository.findAllWithItemsByIdIn(ids).stream()
                .collect(Collectors.toMap(Order::getId, Function.identity()));
    }

    private void validateUpdate(Order order, OrderUpdateDto dto) {
        boolean statusChanges = dto.getStatus() != null && dto.getStatus() != order.getStatus();
        if (order.getStatus() != OrderStatus.NEW && (statusChanges || dto.getItems() != null)) {
            throw new InvalidOrderStateException(
                    "Order in status " + order.getStatus() + " cannot be modified");
        }
    }

    /**
     * Loads all referenced items in one query and fails if any id is missing.
     * Duplicate positions are rejected: two rows with the same item id would
     * break the {@code (order_id, item_id)} uniqueness invariant.
     */
    private Map<UUID, Item> resolveItems(List<OrderItemCreateDto> itemDtos) {
        List<UUID> duplicates = itemDtos.stream()
                .map(OrderItemCreateDto::getItemId)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
        if (!duplicates.isEmpty()) {
            throw new DuplicateOrderItemException("Duplicate item ids in the order: " + duplicates);
        }
        Set<UUID> itemIds = itemDtos.stream()
                .map(OrderItemCreateDto::getItemId)
                .collect(Collectors.toSet());
        Map<UUID, Item> itemsById = itemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(Item::getId, Function.identity()));
        List<UUID> missing = itemIds.stream()
                .filter(id -> !itemsById.containsKey(id))
                .toList();
        if (!missing.isEmpty()) {
            throw new ItemNotFoundException("Items not found: " + missing);
        }
        return itemsById;
    }

    private void addItems(Order order, List<OrderItemCreateDto> itemDtos, Map<UUID, Item> itemsById) {
        for (OrderItemCreateDto itemDto : itemDtos) {
            OrderItem orderItem = OrderItem.builder()
                    .order(order)
                    .item(itemsById.get(itemDto.getItemId()))
                    .quantity(itemDto.getQuantity())
                    .build();
            order.getItems().add(orderItem);
        }
    }

    private BigDecimal calculateTotal(List<OrderItem> orderItems) {
        BigDecimal total = orderItems.stream()
                .map(orderItem -> orderItem.getItem().getPrice()
                        .multiply(BigDecimal.valueOf(orderItem.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        if (total.compareTo(MAX_TOTAL_PRICE) > 0) {
            throw new OrderTotalOverflowException(
                    "Order total must not exceed " + MAX_TOTAL_PRICE);
        }
        return total;
    }
}

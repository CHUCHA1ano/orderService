package com.innowise.orderservice.service;

import com.innowise.orderservice.exception.DuplicateOrderItemException;
import com.innowise.orderservice.exception.InactiveUserException;
import com.innowise.orderservice.exception.InvalidOrderStateException;
import com.innowise.orderservice.exception.ItemNotFoundException;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private UserServiceClient userServiceClient;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private OrderService orderService;

    private UUID userId;
    private UUID orderId;
    private UserDto user;
    private Item itemA;
    private Item itemB;
    private Order order;
    private OrderResponseDto responseDto;

    @BeforeEach
    void setUp() {
        // run the callback right away, no real transaction in unit tests
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });

        userId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        user = new UserDto(userId, "John", "Doe", "john@example.com", true);
        itemA = Item.builder().id(UUID.randomUUID()).name("A").price(new BigDecimal("10.00")).build();
        itemB = Item.builder().id(UUID.randomUUID()).name("B").price(new BigDecimal("5.50")).build();
        order = Order.builder()
                .id(orderId)
                .userId(userId)
                .status(OrderStatus.NEW)
                .totalPrice(BigDecimal.ZERO)
                .build();
        responseDto = OrderResponseDto.builder()
                .id(orderId)
                .userId(userId)
                .status(OrderStatus.NEW)
                .build();
    }

    private OrderCreateDto createDto(OrderItemCreateDto... items) {
        return OrderCreateDto.builder().items(List.of(items)).build();
    }

    private OrderItemCreateDto itemDto(Item item, int quantity) {
        return OrderItemCreateDto.builder().itemId(item.getId()).quantity(quantity).build();
    }

    @Test
    void create_shouldComputeTotalFromItemPrices_andEmbedUser() {
        OrderCreateDto dto = createDto(itemDto(itemA, 2), itemDto(itemB, 1));

        when(userServiceClient.getUserByEmail("john@example.com")).thenReturn(user);
        when(itemRepository.findAllById(Set.of(itemA.getId(), itemB.getId())))
                .thenReturn(List.of(itemA, itemB));
        when(orderMapper.toEntity(dto)).thenReturn(order);
        when(orderRepository.save(order)).thenReturn(order);
        when(orderMapper.toResponse(order, user)).thenReturn(responseDto);

        OrderResponseDto result = orderService.create("john@example.com", dto);

        assertSame(responseDto, result);
        assertEquals(userId, order.getUserId());
        assertEquals(OrderStatus.NEW, order.getStatus());
        assertEquals(new BigDecimal("25.50"), order.getTotalPrice());
        assertEquals(2, order.getItems().size());
        assertEquals(2, order.getItems().get(0).getQuantity());
        assertSame(itemA, order.getItems().get(0).getItem());
        verify(userServiceClient).getUserByEmail("john@example.com");
        verify(orderRepository).save(order);
    }

    @Test
    void create_shouldThrowItemNotFoundException_whenItemDoesNotExist() {
        OrderCreateDto dto = createDto(itemDto(itemA, 1), itemDto(itemB, 1));

        when(userServiceClient.getUserByEmail("john@example.com")).thenReturn(user);
        when(itemRepository.findAllById(Set.of(itemA.getId(), itemB.getId())))
                .thenReturn(List.of(itemA));

        assertThrows(ItemNotFoundException.class, () -> orderService.create("john@example.com", dto));
        verify(orderRepository, never()).save(any());
    }

    @Test
    void create_shouldRejectDuplicateItemIds() {
        OrderCreateDto dto = createDto(itemDto(itemA, 1), itemDto(itemA, 2));

        when(userServiceClient.getUserByEmail("john@example.com")).thenReturn(user);

        assertThrows(DuplicateOrderItemException.class,
                () -> orderService.create("john@example.com", dto));
        verifyNoInteractions(itemRepository);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void create_shouldRejectTotalPriceOverflow() {
        Item expensive = Item.builder()
                .id(UUID.randomUUID())
                .name("Expensive")
                .price(new BigDecimal("99999999.99"))
                .build();
        OrderCreateDto dto = createDto(itemDto(expensive, 1000));

        when(userServiceClient.getUserByEmail("john@example.com")).thenReturn(user);
        when(itemRepository.findAllById(Set.of(expensive.getId())))
                .thenReturn(List.of(expensive));
        when(orderMapper.toEntity(dto)).thenReturn(order);

        assertThrows(OrderTotalOverflowException.class,
                () -> orderService.create("john@example.com", dto));
        verify(orderRepository, never()).save(any());
    }

    @Test
    void create_shouldPropagateUserNotFoundException_andNotOpenTransaction() {
        OrderCreateDto dto = createDto(itemDto(itemA, 1));

        when(userServiceClient.getUserByEmail("missing@example.com"))
                .thenThrow(new UserNotFoundException("User not found: missing@example.com"));

        assertThrows(UserNotFoundException.class, () -> orderService.create("missing@example.com", dto));
        verify(transactionTemplate, never()).execute(any());
        verifyNoInteractions(itemRepository);
    }

    @Test
    void create_shouldRejectInactiveUser_andNotOpenTransaction() {
        OrderCreateDto dto = createDto(itemDto(itemA, 1));
        UserDto inactive = new UserDto(userId, "John", "Doe", "john@example.com", false);

        when(userServiceClient.getUserByEmail("john@example.com")).thenReturn(inactive);

        assertThrows(InactiveUserException.class, () -> orderService.create("john@example.com", dto));
        verify(transactionTemplate, never()).execute(any());
        verifyNoInteractions(itemRepository);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void create_shouldRejectUserWithMissingActiveFlag() {
        OrderCreateDto dto = createDto(itemDto(itemA, 1));
        UserDto noFlag = new UserDto(userId, "John", "Doe", "john@example.com", null);

        when(userServiceClient.getUserByEmail("john@example.com")).thenReturn(noFlag);

        assertThrows(InactiveUserException.class, () -> orderService.create("john@example.com", dto));
        verify(transactionTemplate, never()).execute(any());
    }

    @Test
    void getById_shouldReturnOrderWithUser() {
        when(orderRepository.findByIdWithItems(orderId)).thenReturn(Optional.of(order));
        when(userServiceClient.getUserById(userId)).thenReturn(user);
        when(orderMapper.toResponse(order, user)).thenReturn(responseDto);

        OrderResponseDto result = orderService.getById(orderId);

        assertSame(responseDto, result);
    }

    @Test
    void getById_shouldReturnNullUserWhenProfileIsMissing() {
        when(orderRepository.findByIdWithItems(orderId)).thenReturn(Optional.of(order));
        when(userServiceClient.getUserById(userId))
                .thenThrow(new UserNotFoundException("User not found"));
        when(orderMapper.toResponse(order, null)).thenReturn(responseDto);

        OrderResponseDto result = orderService.getById(orderId);

        assertSame(responseDto, result);
        verify(orderMapper).toResponse(order, null);
    }

    @Test
    void getById_shouldThrowOrderNotFoundException_andSkipUserService() {
        when(orderRepository.findByIdWithItems(orderId)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> orderService.getById(orderId));
        verifyNoInteractions(userServiceClient);
    }

    @Test
    void getAll_shouldMapPageAndEnrichEveryOrder() {
        Pageable pageable = PageRequest.of(0, 10);
        Order second = Order.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .status(OrderStatus.PAID)
                .totalPrice(BigDecimal.TEN)
                .build();

        when(orderRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(order, second), pageable, 2));
        when(orderRepository.findAllWithItemsByIdIn(List.of(order.getId(), second.getId())))
                .thenReturn(List.of(order, second));
        when(userServiceClient.getUserById(userId)).thenReturn(user);
        when(orderMapper.toResponse(order, user)).thenReturn(responseDto);

        Page<OrderResponseDto> page = orderService.getAll(null, null, null, pageable);

        assertEquals(2, page.getTotalElements());
        assertEquals(2, page.getContent().size());
        assertSame(responseDto, page.getContent().get(0));
        verify(userServiceClient, times(2)).getUserById(userId);
    }

    @Test
    void getAll_shouldApplyDateRangeAndStatusFilters() {
        Pageable pageable = PageRequest.of(0, 5);
        OffsetDateTime from = OffsetDateTime.now().minusDays(1);
        OffsetDateTime to = OffsetDateTime.now().plusDays(1);

        when(orderRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(order), pageable, 1));
        when(orderRepository.findAllWithItemsByIdIn(List.of(orderId)))
                .thenReturn(List.of(order));
        when(userServiceClient.getUserById(userId)).thenReturn(user);
        when(orderMapper.toResponse(order, user)).thenReturn(responseDto);

        Page<OrderResponseDto> page = orderService.getAll(from, to,
                List.of(OrderStatus.NEW, OrderStatus.PAID), pageable);

        assertEquals(1, page.getContent().size());
        verify(orderRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    void getAll_shouldIgnoreEmptyStatusList() {
        Pageable pageable = PageRequest.of(0, 5);

        when(orderRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(order), pageable, 1));
        when(orderRepository.findAllWithItemsByIdIn(List.of(orderId)))
                .thenReturn(List.of(order));
        when(userServiceClient.getUserById(userId)).thenReturn(user);
        when(orderMapper.toResponse(order, user)).thenReturn(responseDto);

        Page<OrderResponseDto> page = orderService.getAll(null, null, List.of(), pageable);

        assertEquals(1, page.getContent().size());
    }

    @Test
    void getAll_shouldReturnNullUserForMissingProfile() {
        Pageable pageable = PageRequest.of(0, 10);

        when(orderRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(order), pageable, 1));
        when(orderRepository.findAllWithItemsByIdIn(List.of(orderId)))
                .thenReturn(List.of(order));
        when(userServiceClient.getUserById(userId))
                .thenThrow(new UserNotFoundException("User not found"));
        when(orderMapper.toResponse(order, null)).thenReturn(responseDto);

        Page<OrderResponseDto> page = orderService.getAll(null, null, null, pageable);

        assertEquals(1, page.getContent().size());
        verify(orderMapper).toResponse(order, null);
    }

    @Test
    void getAllByUserId_shouldCallUserServiceOnce() {
        Pageable pageable = PageRequest.of(0, 10);

        when(orderRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(order), pageable, 1));
        when(orderRepository.findAllWithItemsByIdIn(List.of(orderId)))
                .thenReturn(List.of(order));
        when(userServiceClient.getUserById(userId)).thenReturn(user);
        when(orderMapper.toResponse(order, user)).thenReturn(responseDto);

        Page<OrderResponseDto> result = orderService.getAllByUserId(userId, pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals(1, result.getContent().size());
        verify(userServiceClient, times(1)).getUserById(userId);
    }

    @Test
    void getAllByUserId_shouldSkipUserServiceForEmptyPage() {
        Pageable pageable = PageRequest.of(0, 10);

        when(orderRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        Page<OrderResponseDto> result = orderService.getAllByUserId(userId, pageable);

        assertTrue(result.isEmpty());
        verifyNoInteractions(userServiceClient);
    }

    @Test
    void update_shouldChangeStatusOfNewOrder() {
        OrderUpdateDto dto = OrderUpdateDto.builder().status(OrderStatus.PAID).build();

        when(orderRepository.findUserIdById(orderId)).thenReturn(Optional.of(userId));
        when(userServiceClient.getUserById(userId)).thenReturn(user);
        when(orderRepository.findByIdWithItems(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);
        when(orderMapper.toResponse(order, user)).thenReturn(responseDto);

        OrderResponseDto result = orderService.update(orderId, dto, false);

        assertSame(responseDto, result);
        verify(orderMapper).updateEntity(dto, order);
        // profile is loaded once before the transaction and reused in the response
        verify(userServiceClient, times(1)).getUserById(userId);
        // a status-only update is a real row change, no forced bump is needed
        verify(entityManager, never()).lock(any(), any());
    }

    @Test
    void update_shouldReplaceItemsAndRecalculateTotal() {
        order.getItems().add(OrderItem.builder().order(order).item(itemB).quantity(1).build());
        OrderUpdateDto dto = OrderUpdateDto.builder().items(List.of(itemDto(itemA, 3))).build();

        when(orderRepository.findUserIdById(orderId)).thenReturn(Optional.of(userId));
        when(userServiceClient.getUserById(userId)).thenReturn(user);
        when(orderRepository.findByIdWithItems(orderId)).thenReturn(Optional.of(order));
        when(itemRepository.findAllById(Set.of(itemA.getId()))).thenReturn(List.of(itemA));
        when(orderRepository.save(order)).thenReturn(order);
        when(orderMapper.toResponse(order, user)).thenReturn(responseDto);

        orderService.update(orderId, dto, false);

        assertEquals(1, order.getItems().size());
        assertSame(itemA, order.getItems().get(0).getItem());
        assertEquals(new BigDecimal("30.00"), order.getTotalPrice());
        verify(entityManager).lock(order, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
    }

    @Test
    void update_shouldRejectInactiveOwnerBeforeOpeningTransaction() {
        UserDto inactive = new UserDto(userId, "John", "Doe", "john@example.com", false);
        OrderUpdateDto dto = OrderUpdateDto.builder().status(OrderStatus.PAID).build();

        when(orderRepository.findUserIdById(orderId)).thenReturn(Optional.of(userId));
        when(userServiceClient.getUserById(userId)).thenReturn(inactive);

        assertThrows(InactiveUserException.class, () -> orderService.update(orderId, dto, false));
        verify(transactionTemplate, never()).execute(any());
        verify(orderRepository, never()).findByIdWithItems(any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void update_shouldSkipActiveCheckForAdmin() {
        OrderUpdateDto dto = OrderUpdateDto.builder().status(OrderStatus.PAID).build();

        when(orderRepository.findByIdWithItems(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);
        when(userServiceClient.getUserById(userId)).thenReturn(user);
        when(orderMapper.toResponse(order, user)).thenReturn(responseDto);

        OrderResponseDto result = orderService.update(orderId, dto, true);

        assertSame(responseDto, result);
        verify(orderRepository, never()).findUserIdById(any());
        verify(orderMapper).updateEntity(dto, order);
    }

    @Test
    void update_shouldReturnNullUserWhenProfileIsMissing() {
        OrderUpdateDto dto = OrderUpdateDto.builder().status(OrderStatus.PAID).build();

        when(orderRepository.findUserIdById(orderId)).thenReturn(Optional.of(userId));
        when(userServiceClient.getUserById(userId))
                .thenThrow(new UserNotFoundException("User not found"));
        when(orderRepository.findByIdWithItems(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);
        when(orderMapper.toResponse(order, null)).thenReturn(responseDto);

        OrderResponseDto result = orderService.update(orderId, dto, false);

        assertSame(responseDto, result);
        verify(orderMapper).toResponse(order, null);
        verify(userServiceClient, times(1)).getUserById(userId);
    }

    @Test
    void update_shouldRejectChangesToFinalizedOrder() {
        order.setStatus(OrderStatus.PAID);
        when(orderRepository.findUserIdById(orderId)).thenReturn(Optional.of(userId));
        when(userServiceClient.getUserById(userId)).thenReturn(user);
        when(orderRepository.findByIdWithItems(orderId)).thenReturn(Optional.of(order));

        OrderUpdateDto statusChange = OrderUpdateDto.builder().status(OrderStatus.CANCELLED).build();
        assertThrows(InvalidOrderStateException.class,
                () -> orderService.update(orderId, statusChange, false));

        OrderUpdateDto itemsChange = OrderUpdateDto.builder().items(List.of(itemDto(itemA, 1))).build();
        assertThrows(InvalidOrderStateException.class,
                () -> orderService.update(orderId, itemsChange, false));

        verify(orderRepository, never()).save(any());
    }

    @Test
    void update_shouldThrowOrderNotFoundException_whenOrderDoesNotExist() {
        when(orderRepository.findUserIdById(orderId)).thenReturn(Optional.empty());

        OrderUpdateDto dto = OrderUpdateDto.builder().status(OrderStatus.PAID).build();

        assertThrows(OrderNotFoundException.class, () -> orderService.update(orderId, dto, false));
        verifyNoInteractions(userServiceClient);
    }

    @Test
    void delete_shouldSoftDeleteNewOrder() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.softDelete(orderId)).thenReturn(1);

        orderService.delete(orderId);

        verify(orderRepository).softDelete(orderId);
    }

    @Test
    void delete_shouldRejectFinalizedOrder() {
        order.setStatus(OrderStatus.PAID);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThrows(OrderNotDeletableException.class, () -> orderService.delete(orderId));

        verify(orderRepository, never()).softDelete(any());
    }

    @Test
    void delete_shouldThrowOrderNotFoundException_whenOrderDoesNotExist() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> orderService.delete(orderId));

        verify(orderRepository, never()).softDelete(any());
    }

    @Test
    void delete_shouldThrowOrderNotFoundException_whenNothingWasUpdated() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.softDelete(orderId)).thenReturn(0);

        assertThrows(OrderNotFoundException.class, () -> orderService.delete(orderId));
    }
}

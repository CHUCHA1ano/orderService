package com.innowise.orderservice.integration;

import com.innowise.orderservice.model.entity.Item;
import com.innowise.orderservice.model.entity.Order;
import com.innowise.orderservice.model.entity.OrderStatus;
import com.innowise.orderservice.repository.OrderRepository;
import jakarta.persistence.OptimisticLockException;
import org.hibernate.StaleStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that {@code @Version} actually protects against a lost update: two
 * transactions load the same order snapshot, both change it, and only one can
 * commit. The loser's failure chain contains an optimistic-lock exception,
 * which {@code GlobalExceptionHandler} maps to 409.
 */
class OrderOptimisticLockingIntegrationTest extends AbstractOrderIntegrationTest {

    private static final String EMAIL = "john@example.com";

    private final UUID userId = UUID.randomUUID();

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("Two concurrent updates loaded from the same snapshot cannot both commit")
    void concurrentUpdates_shouldFailWithOptimisticLock() throws Exception {
        Item item = createItem("Keyboard", "10.00");
        stubUserByEmail(EMAIL, userId);
        String orderId = createOrder(userJwt(userId, EMAIL), item.getId(), 1);
        UUID orderUuid = UUID.fromString(orderId);

        CountDownLatch bothLoaded = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = executor.submit(
                    () -> updateInTransaction(orderUuid, bothLoaded, OrderStatus.PAID));
            Future<Throwable> second = executor.submit(
                    () -> updateInTransaction(orderUuid, bothLoaded, OrderStatus.CANCELLED));

            List<Throwable> failures = Stream.of(
                            first.get(30, TimeUnit.SECONDS),
                            second.get(30, TimeUnit.SECONDS))
                    .filter(Objects::nonNull)
                    .toList();

            assertEquals(1, failures.size(), "exactly one transaction must lose the race");
            assertTrue(hasOptimisticLockCause(failures.get(0)),
                    () -> "unexpected failure: " + failures.get(0));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1L, jdbcTemplate.queryForObject(
                "select version from orders where id = ?", Long.class, orderUuid));
        String status = jdbcTemplate.queryForObject(
                "select status from orders where id = ?", String.class, orderUuid);
        assertTrue(status.equals("PAID") || status.equals("CANCELLED"),
                "the winner's status must be persisted");
    }

    private Throwable updateInTransaction(UUID orderId, CountDownLatch bothLoaded, OrderStatus newStatus) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                Order order = orderRepository.findById(orderId).orElseThrow();
                bothLoaded.countDown();
                awaitBothLoaded(bothLoaded);
                order.setStatus(newStatus);
                orderRepository.save(order);
            });
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static void awaitBothLoaded(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Both transactions did not load the order in time");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the other transaction", ex);
        }
    }

    private static boolean hasOptimisticLockCause(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ObjectOptimisticLockingFailureException
                    || current instanceof OptimisticLockException
                    || current instanceof StaleStateException) {
                return true;
            }
        }
        return false;
    }
}

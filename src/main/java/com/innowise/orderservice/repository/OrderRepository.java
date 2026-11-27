package com.innowise.orderservice.repository;

import com.innowise.orderservice.model.entity.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    /**
     * Loads the order together with its items in a single query (no N+1),
     * mirroring the findByIdWithCards pattern of userService. The item is fetched
     * too because open-in-view is disabled: the response is mapped after the
     * transaction has closed.
     */
    @Query("select distinct o from Order o left join fetch o.items i left join fetch i.item where o.id = :id")
    Optional<Order> findByIdWithItems(@Param("id") UUID id);

    /**
     * Second step of the list read: one query loads items for the whole page
     * instead of one lazy query per order.
     */
    @EntityGraph(attributePaths = {"items", "items.item"})
    @Query("select distinct o from Order o where o.id in :ids")
    List<Order> findAllWithItemsByIdIn(@Param("ids") Collection<UUID> ids);

    /**
     * Soft delete: the row is kept and flagged, {@code @SQLRestriction} on the
     * entity hides it from all subsequent queries. Never call {@code delete()}
     * here, it would perform a hard delete.
     */
    /**
     * Bulk updates do not bump {@code @Version} automatically, so the version is
     * incremented explicitly: a concurrent PATCH loaded from the pre-delete
     * snapshot then fails with 409 instead of updating a deleted order.
     */
    @Modifying
    @Query("update Order o set o.deleted = true, o.version = o.version + 1 where o.id = :id")
    int softDelete(@Param("id") UUID id);

    /**
     * Native queries bypass {@code @SQLRestriction("deleted = false")}. The access
     * evaluator needs exactly that: a soft-deleted order must look absent (404)
     * instead of looking "not mine" (403).
     */
    @Query(value = "select deleted from orders where id = :id", nativeQuery = true)
    Optional<Boolean> findDeletedByIdIncludingDeleted(@Param("id") UUID id);

    @Query(value = "select user_id from orders where id = :id", nativeQuery = true)
    Optional<UUID> findUserIdByIdIncludingDeleted(@Param("id") UUID id);

    /**
     * Owner lookup used by {@code OrderService.update} to check the actor's
     * active flag before the transaction opens; respects {@code @SQLRestriction}.
     */
    @Query("select o.userId from Order o where o.id = :id")
    Optional<UUID> findUserIdById(@Param("id") UUID id);
}

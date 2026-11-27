package com.innowise.orderservice.repository;

import com.innowise.orderservice.model.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ItemRepository extends JpaRepository<Item, UUID> {

    /**
     * Guards item deletion: the order_items FK is RESTRICT, so a referenced item
     * must produce a readable 409 instead of a low-level constraint violation.
     * <p>
     * Native SQL is used because {@code @SQLRestriction("deleted = false")} on
     * {@code Order} is not applied to an implicit JPQL join from {@code OrderItem};
     * positions of soft-deleted orders must not block deleting a catalog item.
     */
    @Query(value = "select exists ("
            + "select 1 from order_items oi join orders o on o.id = oi.order_id "
            + "where oi.item_id = :itemId and o.deleted = false)",
            nativeQuery = true)
    boolean existsOrderItemByItemId(@Param("itemId") UUID itemId);

    /**
     * The {@code order_items.item_id} FK is RESTRICT, so positions of
     * soft-deleted orders must be removed before the catalog item itself can be
     * deleted. Active orders are handled by the guard above (409), so only rows
     * belonging to deleted orders are touched here.
     */
    @Modifying
    @Query(value = "delete from order_items oi using orders o "
            + "where oi.order_id = o.id and oi.item_id = :itemId and o.deleted = true",
            nativeQuery = true)
    int deleteItemsFromDeletedOrders(@Param("itemId") UUID itemId);
}

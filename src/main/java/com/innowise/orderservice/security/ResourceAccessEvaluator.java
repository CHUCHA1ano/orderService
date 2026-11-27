package com.innowise.orderservice.security;

import com.innowise.orderservice.exception.OrderNotFoundException;
import com.innowise.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * SpEL helper used by {@code @PreAuthorize} to check that a user accesses only their own resources.
 * Admins bypass these checks in the expressions themselves.
 */
@Component("access")
@RequiredArgsConstructor
public class ResourceAccessEvaluator {

    /**
     * Task-compliant claim denoting the authenticated user id. authService emits it
     * alongside the standard {@code sub} claim.
     */
    private static final String USER_ID_CLAIM = "user_id";

    private final OrderRepository orderRepository;

    public boolean isSelf(UUID userId, Authentication authentication) {
        return subject(authentication)
                .map(uid -> uid.equals(userId.toString()))
                .orElse(false);
    }

    /**
     * A soft-deleted order is treated as absent for every caller: its owner gets
     * 404 instead of 403 (the entity is hidden by {@code @SQLRestriction}, so the
     * regular repository cannot see it). Native lookup is used to tell "deleted"
     * apart from "does not exist".
     */
    public boolean isOrderOwner(UUID orderId, Authentication authentication) {
        Optional<String> subject = subject(authentication);
        if (subject.isEmpty()) {
            return false;
        }
        Optional<Boolean> deleted = orderRepository.findDeletedByIdIncludingDeleted(orderId);
        if (deleted.isEmpty() || Boolean.TRUE.equals(deleted.get())) {
            throw new OrderNotFoundException("Order not found: " + orderId);
        }
        return orderRepository.findUserIdByIdIncludingDeleted(orderId)
                .map(ownerId -> subject.get().equals(ownerId.toString()))
                .orElse(false);
    }

    private Optional<String> subject(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return Optional.ofNullable(jwtAuthentication.getToken().getClaimAsString(USER_ID_CLAIM));
        }
        return Optional.empty();
    }
}

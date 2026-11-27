package com.innowise.orderservice.controller;

import com.innowise.orderservice.exception.MissingClaimException;
import com.innowise.orderservice.model.dto.OrderCreateDto;
import com.innowise.orderservice.model.dto.OrderResponseDto;
import com.innowise.orderservice.model.dto.OrderUpdateDto;
import com.innowise.orderservice.model.entity.OrderStatus;
import com.innowise.orderservice.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private static final String EMAIL_CLAIM = "email";

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponseDto> create(Authentication authentication,
                                                   @Valid @RequestBody OrderCreateDto dto) {
        String email = emailClaim(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.create(email, dto));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or @access.isOrderOwner(#id, authentication)")
    public ResponseEntity<OrderResponseDto> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.getById(id));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<OrderResponseDto>> getAll(
            @RequestParam(required = false) OffsetDateTime createdFrom,
            @RequestParam(required = false) OffsetDateTime createdTo,
            @RequestParam(required = false) List<OrderStatus> status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ResponseEntity.ok(orderService.getAll(createdFrom, createdTo, status, pageable));
    }

    /**
     * Orders of one user. Kept under /api/orders (not /api/users/{id}/orders)
     * because the gateway routes /api/users/** to userService.
     */
    @GetMapping("/by-user/{userId}")
    @PreAuthorize("hasRole('ADMIN') or @access.isSelf(#userId, authentication)")
    public ResponseEntity<Page<OrderResponseDto>> getAllByUserId(
            @PathVariable UUID userId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ResponseEntity.ok(orderService.getAllByUserId(userId, pageable));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or @access.isOrderOwner(#id, authentication)")
    public ResponseEntity<OrderResponseDto> update(@PathVariable UUID id,
                                                   @Valid @RequestBody OrderUpdateDto dto,
                                                   Authentication authentication) {
        return ResponseEntity.ok(orderService.update(id, dto, isAdmin(authentication)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or @access.isOrderOwner(#id, authentication)")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        orderService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * The order owner is resolved by email: orderService asks userService for the
     * profile by this claim, so a token without it cannot create an order.
     */
    private static String emailClaim(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            String email = jwtAuthentication.getToken().getClaimAsString(EMAIL_CLAIM);
            if (email != null && !email.isBlank()) {
                return email;
            }
        }
        throw new MissingClaimException("The access token does not contain an email claim");
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}

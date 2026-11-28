package com.sellerdesk.common.security;

import java.util.List;
import java.util.function.Supplier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;

/**
 * Runs code "inside a shop" when there is no logged-in user: courier webhooks, scheduled jobs
 * (docs/modules/COURIER.md §5).
 *
 * <p>Why this exists: tenant services ({@code OrderService}, {@code InventoryService}) read the
 * shop <b>only</b> from {@link CurrentUser} / {@link TenantContext}, so a caller can never pass a
 * wrong shop by mistake. A webhook has no JWT, so both are empty. This class fills them for the
 * duration of {@code work}:
 * <ul>
 *   <li>{@link TenantContext} = {@code shopId}. The caller must take it from a <b>trusted</b> source
 *       (our own shipment record), never from request data.</li>
 *   <li>{@code SecurityContext} = a {@link SystemActor} with no authorities.</li>
 * </ul>
 * Both are restored in {@code finally}, like {@link JwtAuthenticationFilter} does, so a pooled
 * thread never keeps a shop id.
 */
public final class SystemActorScope {

    private static final SecurityContextHolderStrategy CONTEXT = SecurityContextHolder.getContextHolderStrategy();

    private SystemActorScope() {
    }

    public static <T> T runAs(String shopId, SystemActor actor, Supplier<T> work) {
        if (shopId == null || shopId.isBlank()) {
            throw new IllegalArgumentException("shopId is required");
        }
        String previousShop = TenantContext.getShopId();
        SecurityContext previousContext = CONTEXT.getContext();
        try {
            SecurityContext context = CONTEXT.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of()));
            CONTEXT.setContext(context);
            TenantContext.setShopId(shopId);
            return work.get();
        } finally {
            if (previousShop == null) {
                TenantContext.clear();
            } else {
                TenantContext.setShopId(previousShop);
            }
            if (previousContext == null || previousContext.getAuthentication() == null) {
                CONTEXT.clearContext();
            } else {
                CONTEXT.setContext(previousContext);
            }
        }
    }

    public static void runAs(String shopId, SystemActor actor, Runnable work) {
        runAs(shopId, actor, () -> {
            work.run();
            return null;
        });
    }
}

package com.sellerdesk.common.security;

import org.springframework.security.access.AccessDeniedException;

/**
 * Holds the current request's {@code shopId} (the tenant).
 *
 * <p>How it works: a {@link ThreadLocal} is a variable where every thread sees its own value.
 * The web server handles one request per thread, so the value set by
 * {@link JwtAuthenticationFilter} at the start of a request is only visible to code running
 * for that same request.
 *
 * <p>Why it must be cleared: server threads are <b>reused</b> from a pool. If we forgot to clear
 * it, the next request on that thread (maybe from another shop, maybe anonymous) would still see
 * the old shopId. The filter clears it in a {@code finally} block.
 *
 * <p>The value only ever comes from a verified JWT, never from request data.
 * Background jobs ({@code @Scheduled}) and new threads have no TenantContext.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_SHOP_ID = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * Only {@link JwtAuthenticationFilter} and unit tests call this. Public so service tests in
     * other packages can simulate "logged in as shop A". Always pair it with {@link #clear()}.
     */
    public static void setShopId(String shopId) {
        CURRENT_SHOP_ID.set(shopId);
    }

    /** The current shopId, or {@code null} if there is none (anonymous, PLATFORM_ADMIN, background job). */
    public static String getShopId() {
        return CURRENT_SHOP_ID.get();
    }

    /**
     * The current shopId. Use this in tenant services.
     *
     * @throws AccessDeniedException (→ 403) if the request has no shop, e.g. PLATFORM_ADMIN
     */
    public static String requireShopId() {
        String shopId = CURRENT_SHOP_ID.get();
        if (shopId == null) {
            throw new AccessDeniedException("No shop context for this request");
        }
        return shopId;
    }

    public static void clear() {
        CURRENT_SHOP_ID.remove();
    }
}

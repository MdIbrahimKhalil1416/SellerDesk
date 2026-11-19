package com.sellerdesk.common.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Read the logged-in user in services without touching Spring Security classes directly.
 *
 * <pre>{@code
 * Order order = orderRepository.findByIdAndShopId(id, currentUser.shopId())
 *         .orElseThrow(() -> ResourceNotFoundException.of("Order"));
 * order.setCreatedBy(currentUser.userId());
 * }</pre>
 *
 * <p>A bean (not static methods) so service unit tests can mock it:
 * {@code when(currentUser.shopId()).thenReturn("shopA")}.
 */
@Component
public class CurrentUser {

    /** @throws AuthenticationCredentialsNotFoundException (→ 401) if nobody is logged in */
    public AppUserDetails details() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserDetails user)) {
            throw new AuthenticationCredentialsNotFoundException("No authenticated user");
        }
        return user;
    }

    public String userId() {
        return details().userId();
    }

    /**
     * The current tenant. Comes from the verified JWT via {@link TenantContext}.
     *
     * @throws AccessDeniedException (→ 403) if the user has no shop (PLATFORM_ADMIN)
     */
    public String shopId() {
        return TenantContext.requireShopId();
    }

    public Role role() {
        return details().role();
    }

    /** For checks inside a method, e.g. include {@code costPrice} only with REPORT_VIEW. */
    public boolean hasPermission(Permission permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> permission.name().equals(a.getAuthority()));
    }
}

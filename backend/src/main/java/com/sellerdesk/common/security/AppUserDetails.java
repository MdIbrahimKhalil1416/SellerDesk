package com.sellerdesk.common.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Our version of Spring Security's {@link UserDetails}: the logged-in user as Spring sees it.
 *
 * <p>It is a small, read-only copy of the {@code users} document, so it can be cached safely.
 * It lives in {@code common} (not {@code auth}) so security code never depends on a feature module.
 *
 * <p>Authorities: one per permission ({@code ORDER_CREATE}) plus the role as {@code ROLE_SHOP_OWNER}.
 * Code checks permissions; the role authority only exists for completeness.
 *
 * @param userId       users._id
 * @param shopId       the user's shop; {@code null} only for PLATFORM_ADMIN
 * @param email        login identity (lowercase)
 * @param passwordHash BCrypt hash
 * @param enabled      true only when the user's status is ACTIVE
 * @param tokenVersion compared with the {@code ver} claim of every token (revocation)
 */
public record AppUserDetails(
        String userId,
        String shopId,
        String email,
        String passwordHash,
        Role role,
        Set<Permission> permissions,
        boolean enabled,
        int tokenVersion) implements UserDetails {

    public AppUserDetails {
        permissions = Set.copyOf(permissions);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return toAuthorities(role, permissions);
    }

    /** Shared with the JWT filter, which builds authorities from token claims. */
    static List<GrantedAuthority> toAuthorities(Role role, Set<Permission> permissions) {
        List<GrantedAuthority> authorities = new ArrayList<>(permissions.size() + 1);
        permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /** Never print the password hash, even by accident in a log line. */
    @Override
    public String toString() {
        return "AppUserDetails[userId=" + userId + ", shopId=" + shopId + ", role=" + role
                + ", enabled=" + enabled + "]";
    }
}

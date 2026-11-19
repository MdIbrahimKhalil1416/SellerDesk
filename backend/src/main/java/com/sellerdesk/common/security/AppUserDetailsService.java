package com.sellerdesk.common.security;

import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Loads users for Spring Security.
 *
 * <ul>
 *   <li>{@link #loadUserByUsername(String)} (from Spring): by email, used by password login.</li>
 *   <li>{@link #loadUserById(String)}: by id, used by the JWT filter on every request. Cached.</li>
 * </ul>
 *
 * <p>Declared here as an interface and implemented in the {@code auth} feature, so
 * {@code common.security} does not depend on the auth module's repository.
 */
public interface AppUserDetailsService extends UserDetailsService {

    /** @throws UsernameNotFoundException if no user has this id */
    AppUserDetails loadUserById(String userId);

    /**
     * Removes the cached copy of a user. Must be called whenever tokenVersion, status, role or
     * permissions change, otherwise an old token would keep working until the cache expires.
     */
    void evictFromCache(String userId);
}

package com.sellerdesk.auth.service;

import com.sellerdesk.auth.model.User;
import com.sellerdesk.auth.model.UserStatus;
import com.sellerdesk.auth.repository.UserRepository;
import com.sellerdesk.common.security.AppUserDetails;
import com.sellerdesk.common.security.AppUserDetailsService;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads users from MongoDB for Spring Security.
 *
 * <p>{@link #loadUserById} runs on every authenticated request, so its result is cached
 * (Caffeine, 60 s, see application.yml). {@code @Cacheable} works through a Spring proxy: the
 * first call for an id runs the method, later calls return the stored copy without a DB query.
 * Anything that changes a user's tokenVersion/status/role/permissions must call
 * {@link #evictFromCache} so a revoked token stops working immediately, not up to 60 s later.
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements AppUserDetailsService {

    static final String CACHE_NAME = "authUsers";

    private final UserRepository userRepository;

    /** Used by password login. "username" is the email in our app. Not cached. */
    @Override
    public AppUserDetails loadUserByUsername(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return userRepository.findByEmail(normalized)
                .map(UserDetailsServiceImpl::toDetails)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    @Override
    @Cacheable(cacheNames = CACHE_NAME, key = "#userId")
    public AppUserDetails loadUserById(String userId) {
        return userRepository.findById(userId)
                .map(UserDetailsServiceImpl::toDetails)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    @Override
    @CacheEvict(cacheNames = CACHE_NAME, key = "#userId")
    public void evictFromCache(String userId) {
        // The annotation does the work.
    }

    static AppUserDetails toDetails(User user) {
        return new AppUserDetails(
                user.getId(),
                user.getShopId(),
                user.getEmail(),
                user.getPasswordHash(),
                user.getRole(),
                user.getPermissions(),
                user.getStatus() == UserStatus.ACTIVE,
                user.getTokenVersion());
    }
}

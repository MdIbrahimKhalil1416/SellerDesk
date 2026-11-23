package com.sellerdesk.auth.service;

import com.sellerdesk.auth.model.User;
import com.sellerdesk.auth.model.UserStatus;
import com.sellerdesk.auth.repository.UserRepository;
import com.sellerdesk.common.error.BusinessRuleException;
import com.sellerdesk.common.security.AppUserDetailsService;
import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import com.sellerdesk.common.util.Normalize;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * The only way other modules read or change {@code users} (docs/ARCHITECTURE.md §1: {@code auth}
 * owns the collection, {@code staff} changes users through this service).
 *
 * <p>Every change that affects what a user may do (role, permissions, disabling) increments
 * {@code tokenVersion} and evicts the user from the auth cache. Old tokens then fail at the next
 * request, so the new rights apply at once (docs/SECURITY.md §2).
 *
 * <p>Changes use {@code save()}, which checks {@code @Version}: if someone else changed the user
 * in between (another admin, or a logout that bumped tokenVersion), the save fails with
 * {@code 409 CONCURRENT_MODIFICATION} instead of overwriting their change.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final AppUserDetailsService userDetailsService;

    /** Data for a staff member who accepted an invitation. Already validated and normalized. */
    public record NewStaffMember(String shopId, String email, String name, String passwordHash, Role role,
                                 Set<Permission> permissions, String invitedBy, Instant now) {
    }

    /** A user of this shop; another shop's user is "not there". */
    public Optional<User> findInShop(String userId, String shopId) {
        return userRepository.findByIdAndShopId(userId, shopId);
    }

    /** Any user with this email, in any shop. Used to check an email before inviting it. */
    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(Normalize.email(email));
    }

    /**
     * Inserts a new ACTIVE staff member. Called inside the accept transaction.
     *
     * @throws BusinessRuleException {@code 409 EMAIL_ALREADY_REGISTERED} if the email got an
     *                               account in the meantime (the unique index decides)
     */
    public User createStaffMember(NewStaffMember member) {
        User user = new User();
        user.setEmail(member.email());
        user.setName(member.name());
        user.setPasswordHash(member.passwordHash());
        user.setShopId(member.shopId());
        user.setRole(member.role());
        user.setPermissions(copy(member.permissions()));
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion(0);
        user.setInvitedBy(member.invitedBy());
        user.setLastLoginAt(member.now());
        try {
            return userRepository.save(user);
        } catch (DuplicateKeyException e) {
            throw new BusinessRuleException(AuthService.EMAIL_ALREADY_REGISTERED,
                    "An account with this email already exists");
        }
    }

    /** New role and permissions; all of the user's sessions end (tokenVersion + 1). */
    public User changeAccess(User user, Role role, Set<Permission> permissions) {
        user.setRole(role);
        user.setPermissions(copy(permissions));
        user.setTokenVersion(user.getTokenVersion() + 1);
        return saveAndEvict(user);
    }

    /**
     * ACTIVE ↔ DISABLED. Disabling ends every session (tokenVersion + 1); a disabled user can no
     * longer log in or refresh. Reactivating keeps role and permissions; the user logs in again.
     */
    public User changeStatus(User user, UserStatus status) {
        if (status == UserStatus.DISABLED) {
            user.setTokenVersion(user.getTokenVersion() + 1);
        }
        user.setStatus(status);
        return saveAndEvict(user);
    }

    private User saveAndEvict(User user) {
        User saved = userRepository.save(user);
        // After the save: the next request reloads the user and sees the new tokenVersion/status.
        userDetailsService.evictFromCache(saved.getId());
        return saved;
    }

    private static Set<Permission> copy(Set<Permission> permissions) {
        return permissions.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(permissions);
    }
}

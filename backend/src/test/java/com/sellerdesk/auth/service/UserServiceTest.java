package com.sellerdesk.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerdesk.auth.model.User;
import com.sellerdesk.auth.model.UserStatus;
import com.sellerdesk.auth.repository.UserRepository;
import com.sellerdesk.common.error.BusinessRuleException;
import com.sellerdesk.common.security.AppUserDetailsService;
import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;

/** Every access change ends the user's sessions (tokenVersion + 1) and evicts the auth cache. */
class UserServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AppUserDetailsService userDetailsService = mock(AppUserDetailsService.class);
    private final UserService service = new UserService(userRepository, userDetailsService);

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId("u1");
        user.setRole(Role.MODERATOR);
        user.setPermissions(EnumSet.copyOf(Role.MODERATOR.defaultPermissions()));
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion(3);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void changeAccessBumpsTokenVersionThenEvicts() {
        User saved = service.changeAccess(user, Role.PACKER, Set.of(Permission.ORDER_VIEW));

        assertThat(saved.getRole()).isEqualTo(Role.PACKER);
        assertThat(saved.getPermissions()).containsExactly(Permission.ORDER_VIEW);
        assertThat(saved.getTokenVersion()).isEqualTo(4);
        InOrder order = inOrder(userRepository, userDetailsService);
        order.verify(userRepository).save(user);
        order.verify(userDetailsService).evictFromCache("u1");
    }

    @Test
    void changeAccessToNoPermissionsWorks() {
        assertThat(service.changeAccess(user, Role.PACKER, Set.of()).getPermissions()).isEmpty();
    }

    @Test
    void disablingBumpsTokenVersionAndEvicts() {
        User saved = service.changeStatus(user, UserStatus.DISABLED);

        assertThat(saved.getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThat(saved.getTokenVersion()).isEqualTo(4);
        InOrder order = inOrder(userRepository, userDetailsService);
        order.verify(userRepository).save(user);
        order.verify(userDetailsService).evictFromCache("u1");
    }

    @Test
    void reactivatingKeepsTokenVersionRoleAndPermissionsButEvicts() {
        user.setStatus(UserStatus.DISABLED);
        User saved = service.changeStatus(user, UserStatus.ACTIVE);

        assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(saved.getTokenVersion()).isEqualTo(3);
        assertThat(saved.getRole()).isEqualTo(Role.MODERATOR);
        assertThat(saved.getPermissions()).isEqualTo(Role.MODERATOR.defaultPermissions());
        org.mockito.Mockito.verify(userDetailsService).evictFromCache("u1");
    }

    @Test
    void createStaffMemberIsActiveWithInvitationData() {
        Instant now = Instant.parse("2026-11-20T10:00:00Z");
        User created = service.createStaffMember(new UserService.NewStaffMember("shopA", "karim@example.com", "Karim",
                "$2a$hash", Role.PACKER, Set.of(Permission.ORDER_VIEW), "owner1", now));

        assertThat(created.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(created.getShopId()).isEqualTo("shopA");
        assertThat(created.getRole()).isEqualTo(Role.PACKER);
        assertThat(created.getPermissions()).containsExactly(Permission.ORDER_VIEW);
        assertThat(created.getInvitedBy()).isEqualTo("owner1");
        assertThat(created.getLastLoginAt()).isEqualTo(now);
        assertThat(created.getTokenVersion()).isZero();
    }

    @Test
    void createStaffMemberWithTakenEmailIs409() {
        when(userRepository.save(any(User.class))).thenThrow(new DuplicateKeyException("email"));
        assertThatThrownBy(() -> service.createStaffMember(new UserService.NewStaffMember("shopA", "x@example.com",
                "X", "h", Role.PACKER, Set.of(), "o", Instant.now())))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "EMAIL_ALREADY_REGISTERED");
    }
}

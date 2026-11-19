package com.sellerdesk.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerdesk.auth.model.User;
import com.sellerdesk.auth.model.UserStatus;
import com.sellerdesk.auth.repository.UserRepository;
import com.sellerdesk.common.security.AppUserDetails;
import com.sellerdesk.common.security.Role;
import java.util.EnumSet;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

class UserDetailsServiceImplTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserDetailsServiceImpl service = new UserDetailsServiceImpl(userRepository);

    @Test
    void loadByIdMapsAllFields() {
        User user = user(UserStatus.ACTIVE);
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));

        AppUserDetails details = service.loadUserById("u1");

        assertThat(details.userId()).isEqualTo("u1");
        assertThat(details.shopId()).isEqualTo("shopA");
        assertThat(details.getUsername()).isEqualTo("rahim@example.com");
        assertThat(details.getPassword()).isEqualTo("$2a$12$hash");
        assertThat(details.role()).isEqualTo(Role.MODERATOR);
        assertThat(details.permissions()).isEqualTo(Role.MODERATOR.defaultPermissions());
        assertThat(details.tokenVersion()).isEqualTo(4);
        assertThat(details.isEnabled()).isTrue();
    }

    @Test
    void onlyActiveUsersAreEnabled() {
        for (UserStatus status : UserStatus.values()) {
            when(userRepository.findById("u1")).thenReturn(Optional.of(user(status)));
            assertThat(service.loadUserById("u1").isEnabled())
                    .as(status.name())
                    .isEqualTo(status == UserStatus.ACTIVE);
        }
    }

    @Test
    void loadByUsernameNormalizesEmail() {
        when(userRepository.findByEmail("rahim@example.com")).thenReturn(Optional.of(user(UserStatus.ACTIVE)));

        assertThat(service.loadUserByUsername("  Rahim@Example.COM ").userId()).isEqualTo("u1");
    }

    @Test
    void unknownUserThrows() {
        when(userRepository.findById("nope")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("nope@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserById("nope")).isInstanceOf(UsernameNotFoundException.class);
        assertThatThrownBy(() -> service.loadUserByUsername("nope@example.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void toStringNeverContainsPasswordHash() {
        when(userRepository.findById("u1")).thenReturn(Optional.of(user(UserStatus.ACTIVE)));

        assertThat(service.loadUserById("u1").toString()).doesNotContain("$2a$12$hash");
        assertThat(user(UserStatus.ACTIVE).toString()).doesNotContain("$2a$12$hash");
    }

    private static User user(UserStatus status) {
        User user = new User();
        user.setId("u1");
        user.setShopId("shopA");
        user.setEmail("rahim@example.com");
        user.setPasswordHash("$2a$12$hash");
        user.setRole(Role.MODERATOR);
        user.setPermissions(EnumSet.copyOf(Role.MODERATOR.defaultPermissions()));
        user.setStatus(status);
        user.setTokenVersion(4);
        return user;
    }
}

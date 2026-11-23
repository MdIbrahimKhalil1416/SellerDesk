package com.sellerdesk.staff.service;

import static com.sellerdesk.common.security.Permission.ORDER_VIEW;
import static com.sellerdesk.common.security.Permission.REPORT_VIEW;
import static com.sellerdesk.common.security.Permission.STAFF_MANAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerdesk.auth.dto.UserResponse;
import com.sellerdesk.auth.model.User;
import com.sellerdesk.auth.service.AuthService;
import com.sellerdesk.auth.service.UserService;
import com.sellerdesk.common.error.AppException;
import com.sellerdesk.common.error.FieldValidationException;
import com.sellerdesk.common.error.RateLimitedException;
import com.sellerdesk.common.error.ResourceNotFoundException;
import com.sellerdesk.common.security.AppUserDetails;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import com.sellerdesk.shop.dto.ShopSummary;
import com.sellerdesk.shop.model.ShopStatus;
import com.sellerdesk.shop.service.ShopService;
import com.sellerdesk.staff.dto.AcceptInvitationRequest;
import com.sellerdesk.staff.dto.InvitationPreviewResponse;
import com.sellerdesk.staff.dto.InvitationResponse;
import com.sellerdesk.staff.dto.InviteStaffRequest;
import com.sellerdesk.staff.model.InvitationStatus;
import com.sellerdesk.staff.model.StaffInvitation;
import com.sellerdesk.staff.repository.StaffInvitationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionOperations;

/** Business rules of {@link InvitationService}. Logged in as the owner of "shopA" unless a test says otherwise. */
class InvitationServiceTest {

    private static final String SHOP = "shopA";
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private final StaffInvitationRepository repository = mock(StaffInvitationRepository.class);
    private final UserService userService = mock(UserService.class);
    private final AuthService authService = mock(AuthService.class);
    private final ShopService shopService = mock(ShopService.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final InvitationService service = new InvitationService(repository, userService, authService, shopService,
            currentUser, passwordEncoder, TransactionOperations.withoutTransaction(), events,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        loginAs("owner", Role.SHOP_OWNER, EnumSet.allOf(Permission.class));
        User owner = new User();
        owner.setId("owner");
        owner.setName("Rahim");
        when(userService.findInShop("owner", SHOP)).thenReturn(Optional.of(owner));
        when(userService.findByEmail(any())).thenReturn(Optional.empty());
        when(shopService.findSummary(SHOP)).thenReturn(Optional.of(new ShopSummary(SHOP, "Rahim Fashion", ShopStatus.ACTIVE)));
        when(repository.findByShopIdAndEmailAndStatus(any(), any(), any())).thenReturn(Optional.empty());
        when(repository.save(any(StaffInvitation.class))).thenAnswer(inv -> {
            StaffInvitation i = inv.getArgument(0);
            if (i.getId() == null) {
                i.setId("inv1");
            }
            return i;
        });
    }

    private void loginAs(String userId, Role role, Set<Permission> permissions) {
        when(currentUser.shopId()).thenReturn(SHOP);
        when(currentUser.userId()).thenReturn(userId);
        when(currentUser.details()).thenReturn(
                new AppUserDetails(userId, SHOP, userId + "@example.com", "h", role, permissions, true, 0));
    }

    private StaffInvitedEvent publishedEvent() {
        ArgumentCaptor<StaffInvitedEvent> event = ArgumentCaptor.forClass(StaffInvitedEvent.class);
        verify(events).publishEvent(event.capture());
        return event.getValue();
    }

    private static StaffInvitation pending(String token, Instant expiresAt) {
        StaffInvitation invitation = new StaffInvitation();
        invitation.setId("inv1");
        invitation.setShopId(SHOP);
        invitation.setEmail("karim@example.com");
        invitation.setRole(Role.PACKER);
        invitation.setPermissions(EnumSet.copyOf(Role.PACKER.defaultPermissions()));
        invitation.setStatus(InvitationStatus.PENDING);
        invitation.setTokenHash(InvitationTokens.hash(token));
        invitation.setExpiresAt(expiresAt);
        invitation.setInvitedBy("owner");
        invitation.setSendCount(1);
        invitation.setLastSentAt(expiresAt.minus(StaffInvitation.VALIDITY));
        invitation.setRecentSends(new ArrayList<>(List.of(invitation.getLastSentAt())));
        return invitation;
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(AppException.class).hasFieldOrPropertyWithValue("code", code);
    }

    // ==== invite ===========================================================================

    @Nested
    class Invite {

        @Test
        void createsPendingInvitationWithRoleDefaultsAndSendsEmailEvent() {
            InvitationResponse response = service.invite(new InviteStaffRequest(" Karim@Example.com ", "PACKER", null));

            ArgumentCaptor<StaffInvitation> saved = ArgumentCaptor.forClass(StaffInvitation.class);
            verify(repository).save(saved.capture());
            StaffInvitation invitation = saved.getValue();
            assertThat(invitation.getShopId()).isEqualTo(SHOP);
            assertThat(invitation.getEmail()).isEqualTo("karim@example.com");
            assertThat(invitation.getRole()).isEqualTo(Role.PACKER);
            assertThat(invitation.getPermissions()).isEqualTo(Role.PACKER.defaultPermissions());
            assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.PENDING);
            assertThat(invitation.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
            assertThat(invitation.getSendCount()).isEqualTo(1);
            assertThat(invitation.getRecentSends()).containsExactly(NOW);
            assertThat(invitation.getInvitedBy()).isEqualTo("owner");

            StaffInvitedEvent event = publishedEvent();
            // Only the hash is stored; the plain token travels in the event (→ email) only.
            assertThat(invitation.getTokenHash()).isEqualTo(InvitationTokens.hash(event.token()))
                    .isNotEqualTo(event.token());
            assertThat(event.token()).hasSize(43);
            assertThat(event.shopName()).isEqualTo("Rahim Fashion");
            assertThat(event.inviterName()).isEqualTo("Rahim");
            assertThat(response.status()).isEqualTo(InvitationStatus.PENDING);
        }

        @Test
        void customPermissionsAreUsed() {
            service.invite(new InviteStaffRequest("karim@example.com", "PACKER", List.of("ORDER_VIEW", "REPORT_VIEW")));
            ArgumentCaptor<StaffInvitation> saved = ArgumentCaptor.forClass(StaffInvitation.class);
            verify(repository).save(saved.capture());
            assertThat(saved.getValue().getPermissions()).containsExactlyInAnyOrder(ORDER_VIEW, REPORT_VIEW);
        }

        @Test
        void memberOfThisShopIs409AlreadyAMember() {
            User member = new User();
            member.setShopId(SHOP);
            when(userService.findByEmail("karim@example.com")).thenReturn(Optional.of(member));

            assertCode(() -> service.invite(new InviteStaffRequest("karim@example.com", "PACKER", null)), "ALREADY_A_MEMBER");
            verify(repository, never()).save(any());
        }

        @Test
        void accountInAnotherShopIs409EmailAlreadyRegistered() {
            User elsewhere = new User();
            elsewhere.setShopId("shopB");
            when(userService.findByEmail("karim@example.com")).thenReturn(Optional.of(elsewhere));

            assertCode(() -> service.invite(new InviteStaffRequest("karim@example.com", "PACKER", null)),
                    "EMAIL_ALREADY_REGISTERED");
        }

        @Test
        void platformAdminEmailIs409EmailAlreadyRegistered() {
            User admin = new User(); // no shopId
            when(userService.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));

            assertCode(() -> service.invite(new InviteStaffRequest("admin@example.com", "PACKER", null)),
                    "EMAIL_ALREADY_REGISTERED");
        }

        @Test
        void openInvitationIs409AlreadyInvited() {
            when(repository.findByShopIdAndEmailAndStatus(SHOP, "karim@example.com", InvitationStatus.PENDING))
                    .thenReturn(Optional.of(pending("t", NOW.plus(Duration.ofDays(1)))));

            assertCode(() -> service.invite(new InviteStaffRequest("karim@example.com", "PACKER", null)), "ALREADY_INVITED");
            verify(repository, never()).save(any());
        }

        @Test
        void expiredInvitationIsReusedWithNewRoleAndToken() {
            StaffInvitation expired = pending("old-token", NOW.minus(Duration.ofDays(1)));
            when(repository.findByShopIdAndEmailAndStatus(SHOP, "karim@example.com", InvitationStatus.PENDING))
                    .thenReturn(Optional.of(expired));

            InvitationResponse response = service.invite(new InviteStaffRequest("karim@example.com", "ACCOUNTANT", null));

            assertThat(response.id()).isEqualTo("inv1");
            assertThat(expired.getRole()).isEqualTo(Role.ACCOUNTANT);
            assertThat(expired.getPermissions()).isEqualTo(Role.ACCOUNTANT.defaultPermissions());
            assertThat(expired.getTokenHash()).isNotEqualTo(InvitationTokens.hash("old-token"));
            assertThat(expired.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
            assertThat(expired.getSendCount()).isEqualTo(2);
        }

        @Test
        void raceOnPartialUniqueIndexIs409AlreadyInvited() {
            when(repository.save(any(StaffInvitation.class))).thenThrow(new DuplicateKeyException("dup"));
            assertCode(() -> service.invite(new InviteStaffRequest("karim@example.com", "PACKER", null)), "ALREADY_INVITED");
        }

        @Test
        void nonOwnerCannotInviteWithPermissionsTheyLack() {
            loginAs("manager", Role.MODERATOR, EnumSet.of(STAFF_MANAGE, ORDER_VIEW));

            assertCode(() -> service.invite(new InviteStaffRequest("k@example.com", "PACKER", List.of("REPORT_VIEW"))),
                    "PERMISSION_NOT_GRANTABLE");
            // The role's defaults count as granted too (PACKER has ORDER_FULFILL, the manager does not).
            assertCode(() -> service.invite(new InviteStaffRequest("k@example.com", "PACKER", null)),
                    "PERMISSION_NOT_GRANTABLE");
            verify(repository, never()).save(any());
        }

        @Test
        void nonOwnerMayInviteWithOwnPermissions() {
            loginAs("manager", Role.MODERATOR, EnumSet.of(STAFF_MANAGE, ORDER_VIEW));
            service.invite(new InviteStaffRequest("k@example.com", "PACKER", List.of("ORDER_VIEW")));
            verify(repository).save(any());
        }

        @Test
        void ownerRoleCannotBeInvited() {
            assertThatThrownBy(() -> service.invite(new InviteStaffRequest("k@example.com", "SHOP_OWNER", null)))
                    .isInstanceOf(FieldValidationException.class);
        }
    }

    // ==== resend / cancel ==================================================================

    @Nested
    class ResendAndCancel {

        @Test
        void resendRotatesTokenExtendsExpiryAndCounts() {
            StaffInvitation invitation = pending("old-token", NOW.plus(Duration.ofDays(7)).minus(Duration.ofHours(1))); // sent 1 h ago
            when(repository.findByIdAndShopId("inv1", SHOP)).thenReturn(Optional.of(invitation));

            service.resend("inv1");

            StaffInvitedEvent event = publishedEvent();
            assertThat(invitation.getTokenHash()).isEqualTo(InvitationTokens.hash(event.token()))
                    .isNotEqualTo(InvitationTokens.hash("old-token"));
            assertThat(invitation.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
            assertThat(invitation.getSendCount()).isEqualTo(2);
            assertThat(invitation.getLastSentAt()).isEqualTo(NOW);
            assertThat(invitation.getRecentSends()).hasSize(2).contains(NOW);
        }

        @Test
        void resendWorksForExpiredInvitations() {
            StaffInvitation expired = pending("t", NOW.minus(Duration.ofDays(2)));
            when(repository.findByIdAndShopId("inv1", SHOP)).thenReturn(Optional.of(expired));

            service.resend("inv1");
            assertThat(expired.isExpired(NOW)).isFalse();
        }

        @Test
        void resendWithin60SecondsIs429() {
            StaffInvitation invitation = pending("t", NOW.plus(Duration.ofDays(7)).minusSeconds(30)); // sent 30 s ago
            when(repository.findByIdAndShopId("inv1", SHOP)).thenReturn(Optional.of(invitation));

            assertThatThrownBy(() -> service.resend("inv1")).isInstanceOf(RateLimitedException.class)
                    .hasFieldOrPropertyWithValue("code", "INVITE_RESEND_TOO_SOON")
                    .hasFieldOrPropertyWithValue("retryAfterSeconds", 30L);
            verify(repository, never()).save(any());
        }

        @Test
        void sixthSendIn24HoursIs429() {
            StaffInvitation invitation = pending("t", NOW.plus(Duration.ofDays(6)));
            invitation.setRecentSends(new ArrayList<>(List.of(NOW.minus(Duration.ofHours(5)), NOW.minus(Duration.ofHours(4)),
                    NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofHours(1)))));
            invitation.setLastSentAt(NOW.minus(Duration.ofHours(1)));
            when(repository.findByIdAndShopId("inv1", SHOP)).thenReturn(Optional.of(invitation));

            assertCode(() -> service.resend("inv1"), "INVITE_RESEND_LIMIT");
        }

        @Test
        void resendOrCancelOfNonPendingIs409() {
            StaffInvitation cancelled = pending("t", NOW.plus(Duration.ofDays(1)));
            cancelled.setStatus(InvitationStatus.CANCELLED);
            when(repository.findByIdAndShopId("inv1", SHOP)).thenReturn(Optional.of(cancelled));

            assertCode(() -> service.resend("inv1"), "INVITATION_NOT_PENDING");
            assertCode(() -> service.cancel("inv1"), "INVITATION_NOT_PENDING");
        }

        @Test
        void unknownOrOtherShopsInvitationIs404() {
            when(repository.findByIdAndShopId("x", SHOP)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resend("x")).isInstanceOf(ResourceNotFoundException.class)
                    .hasFieldOrPropertyWithValue("code", "NOT_FOUND");
            assertThatThrownBy(() -> service.cancel("x")).isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void cancelSetsCancelled() {
            StaffInvitation invitation = pending("t", NOW.plus(Duration.ofDays(1)));
            when(repository.findByIdAndShopId("inv1", SHOP)).thenReturn(Optional.of(invitation));

            service.cancel("inv1");
            assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.CANCELLED);
            verify(repository).save(invitation);
        }
    }

    // ==== preview / accept =================================================================

    @Nested
    class Accept {

        private StaffInvitation invitation;

        @BeforeEach
        void validInvitation() {
            invitation = pending("good-token", NOW.plus(Duration.ofDays(3)));
            when(repository.findByTokenHash(any())).thenReturn(Optional.empty());
            when(repository.findByTokenHash(InvitationTokens.hash("good-token"))).thenReturn(Optional.of(invitation));
            when(passwordEncoder.encode("karim2026pass")).thenReturn("$2a$hash");
            when(userService.createStaffMember(any())).thenAnswer(inv -> {
                UserService.NewStaffMember m = inv.getArgument(0);
                User u = new User();
                u.setId("newUser");
                u.setShopId(m.shopId());
                u.setRole(m.role());
                return u;
            });
            when(authService.startSession(any())).thenReturn(new AuthService.AuthResult("access", "refresh", 900,
                    new UserResponse("newUser", "Karim", "karim@example.com", null, Role.PACKER, List.of(), null)));
        }

        private AcceptInvitationRequest accept(String token) {
            return new AcceptInvitationRequest(token, " Karim Hossain ", "karim2026pass");
        }

        @Test
        void previewShowsShopRoleAndInviter() {
            InvitationPreviewResponse preview = service.preview("good-token");
            assertThat(preview).isEqualTo(new InvitationPreviewResponse("karim@example.com", "Rahim Fashion",
                    Role.PACKER, "Rahim", invitation.getExpiresAt()));
        }

        @Test
        void acceptCreatesActiveUserFromInvitationAndMarksAccepted() {
            AuthService.AuthResult result = service.accept(accept("good-token"));

            ArgumentCaptor<UserService.NewStaffMember> member = ArgumentCaptor.forClass(UserService.NewStaffMember.class);
            verify(userService).createStaffMember(member.capture());
            assertThat(member.getValue()).isEqualTo(new UserService.NewStaffMember(SHOP, "karim@example.com",
                    "Karim Hossain", "$2a$hash", Role.PACKER, invitation.getPermissions(), "owner", NOW));
            assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
            assertThat(invitation.getAcceptedUserId()).isEqualTo("newUser");
            verify(repository).save(invitation);
            assertThat(result.accessToken()).isEqualTo("access");
        }

        @Test
        void secondAcceptFails() {
            service.accept(accept("good-token"));
            assertCode(() -> service.accept(accept("good-token")), "INVALID_INVITATION");
        }

        @Test
        void unknownTokenIsInvalid() {
            assertCode(() -> service.preview("nope"), "INVALID_INVITATION");
            assertCode(() -> service.accept(accept("nope")), "INVALID_INVITATION");
        }

        @Test
        void cancelledInvitationIsInvalid() {
            invitation.setStatus(InvitationStatus.CANCELLED);
            assertCode(() -> service.accept(accept("good-token")), "INVALID_INVITATION");
        }

        @Test
        void expiredTokenIsExpired() {
            invitation.setExpiresAt(NOW);
            assertCode(() -> service.preview("good-token"), "INVITATION_EXPIRED");
            assertCode(() -> service.accept(accept("good-token")), "INVITATION_EXPIRED");
            verify(userService, never()).createStaffMember(any());
        }

        @Test
        void inactiveShopIsInvalid() {
            when(shopService.findSummary(SHOP)).thenReturn(Optional.of(new ShopSummary(SHOP, "X", ShopStatus.SUSPENDED)));
            assertCode(() -> service.accept(accept("good-token")), "INVALID_INVITATION");
        }

        @Test
        void emailRegisteredMeanwhileIs409() {
            when(userService.findByEmail("karim@example.com")).thenReturn(Optional.of(new User()));
            assertCode(() -> service.accept(accept("good-token")), "EMAIL_ALREADY_REGISTERED");
            verify(userService, never()).createStaffMember(any());
        }

        @Test
        void passwordEqualToEmailIs400() {
            assertThatThrownBy(() -> service.accept(new AcceptInvitationRequest("good-token", "K", "Karim@example.com")))
                    .isInstanceOf(FieldValidationException.class);
        }

        @Test
        void parallelChangeOfTheInvitationMakesAcceptInvalid() {
            when(repository.save(any(StaffInvitation.class))).thenThrow(new OptimisticLockingFailureException("v"));
            assertCode(() -> service.accept(accept("good-token")), "INVALID_INVITATION");
            verify(authService, never()).startSession(any());
        }
    }
}

package com.sellerdesk.staff.service;

import static com.sellerdesk.common.security.Permission.ORDER_FULFILL;
import static com.sellerdesk.common.security.Permission.ORDER_VIEW;
import static com.sellerdesk.common.security.Permission.REPORT_VIEW;
import static com.sellerdesk.common.security.Permission.STAFF_MANAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sellerdesk.auth.model.User;
import com.sellerdesk.auth.model.UserStatus;
import com.sellerdesk.auth.service.UserService;
import com.sellerdesk.common.dto.PageResponse;
import com.sellerdesk.common.error.AppException;
import com.sellerdesk.common.error.FieldValidationException;
import com.sellerdesk.common.error.ResourceNotFoundException;
import com.sellerdesk.common.security.AppUserDetails;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import com.sellerdesk.staff.dto.PermissionCatalogResponse;
import com.sellerdesk.staff.dto.StaffResponse;
import com.sellerdesk.staff.dto.StaffRow;
import com.sellerdesk.staff.dto.StaffStatusRequest;
import com.sellerdesk.staff.dto.UpdateStaffRequest;
import com.sellerdesk.staff.repository.StaffQueryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Business rules of {@link StaffService}. The shop is always "shopA" from {@link CurrentUser}. */
class StaffServiceTest {

    private static final String SHOP = "shopA";
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private final UserService userService = mock(UserService.class);
    private final StaffQueryRepository queryRepository = mock(StaffQueryRepository.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final StaffService service = new StaffService(userService, queryRepository, currentUser,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private User moderator;

    @BeforeEach
    void loggedInAsOwnerOfShopA() {
        loginAs("owner", Role.SHOP_OWNER, EnumSet.allOf(Permission.class));
        moderator = member("m1", Role.MODERATOR);
        when(userService.findInShop("m1", SHOP)).thenReturn(Optional.of(moderator));
        when(userService.changeAccess(any(), any(), any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setRole(inv.getArgument(1));
            u.setPermissions(EnumSet.copyOf((Set<Permission>) inv.getArgument(2)));
            return u;
        });
        when(userService.changeStatus(any(), any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setStatus(inv.getArgument(1));
            return u;
        });
    }

    private void loginAs(String userId, Role role, Set<Permission> permissions) {
        when(currentUser.shopId()).thenReturn(SHOP);
        when(currentUser.userId()).thenReturn(userId);
        when(currentUser.details()).thenReturn(
                new AppUserDetails(userId, SHOP, userId + "@example.com", "h", role, permissions, true, 0));
    }

    private static User member(String id, Role role) {
        User user = new User();
        user.setId(id);
        user.setShopId(SHOP);
        user.setName(id);
        user.setRole(role);
        user.setPermissions(EnumSet.copyOf(role.defaultPermissions()));
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }

    // ==== update ===========================================================================

    @Test
    void roleChangeWithoutPermissionsResetsToRoleDefaults() {
        StaffResponse response = service.update("m1", new UpdateStaffRequest("PACKER", null));

        verify(userService).changeAccess(moderator, Role.PACKER, Role.PACKER.defaultPermissions());
        assertThat(response.role()).isEqualTo(Role.PACKER);
        assertThat(response.permissions()).containsExactlyElementsOf(
                Role.PACKER.defaultPermissions().stream().sorted().toList());
    }

    @Test
    void roleChangeWithPermissionsUsesTheGivenList() {
        service.update("m1", new UpdateStaffRequest("PACKER", List.of("ORDER_VIEW", "REPORT_VIEW")));
        verify(userService).changeAccess(moderator, Role.PACKER, EnumSet.of(ORDER_VIEW, REPORT_VIEW));
    }

    @Test
    void permissionsOnlyKeepsTheRole() {
        service.update("m1", new UpdateStaffRequest(null, List.of("ORDER_VIEW")));
        verify(userService).changeAccess(moderator, Role.MODERATOR, EnumSet.of(ORDER_VIEW));
    }

    @Test
    void sameRoleWithoutPermissionsKeepsCustomPermissionsAndDoesNotLogOut() {
        moderator.setPermissions(EnumSet.of(ORDER_VIEW, REPORT_VIEW));
        StaffResponse response = service.update("m1", new UpdateStaffRequest("MODERATOR", null));

        verify(userService, never()).changeAccess(any(), any(), any()); // no tokenVersion bump
        assertThat(response.permissions()).containsExactly(ORDER_VIEW, REPORT_VIEW);
    }

    @Test
    void emptyRequestChangesNothing() {
        service.update("m1", new UpdateStaffRequest(null, null));
        verify(userService, never()).changeAccess(any(), any(), any());
    }

    @Test
    void unknownPermissionIs400() {
        assertThatThrownBy(() -> service.update("m1", new UpdateStaffRequest(null, List.of("FLY"))))
                .isInstanceOf(FieldValidationException.class);
        verify(userService, never()).changeAccess(any(), any(), any());
    }

    @Test
    void ownerCannotBeEdited() {
        User owner = member("owner2", Role.SHOP_OWNER);
        when(userService.findInShop("owner2", SHOP)).thenReturn(Optional.of(owner));
        loginAs("manager", Role.MODERATOR, EnumSet.of(STAFF_MANAGE));

        assertCode(() -> service.update("owner2", new UpdateStaffRequest("PACKER", null)), "OWNER_READ_ONLY");
        assertCode(() -> service.changeStatus("owner2", new StaffStatusRequest("DISABLED")), "OWNER_READ_ONLY");
        verify(userService, never()).changeAccess(any(), any(), any());
        verify(userService, never()).changeStatus(any(), any());
    }

    @Test
    void ownerCannotDemoteOrDeactivateThemselves() {
        User owner = member("owner", Role.SHOP_OWNER);
        when(userService.findInShop("owner", SHOP)).thenReturn(Optional.of(owner));

        assertCode(() -> service.update("owner", new UpdateStaffRequest("MODERATOR", null)), "OWNER_READ_ONLY");
        assertCode(() -> service.changeStatus("owner", new StaffStatusRequest("DISABLED")), "OWNER_READ_ONLY");
        assertThat(owner.getRole()).isEqualTo(Role.SHOP_OWNER);
        assertThat(owner.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void staffCannotChangeThemselves() {
        loginAs("m1", Role.MODERATOR, EnumSet.of(STAFF_MANAGE, ORDER_VIEW));

        assertCode(() -> service.update("m1", new UpdateStaffRequest(null, List.of("ORDER_VIEW"))), "CANNOT_MODIFY_SELF");
        assertCode(() -> service.changeStatus("m1", new StaffStatusRequest("DISABLED")), "CANNOT_MODIFY_SELF");
    }

    @Test
    void nonOwnerCannotGrantAPermissionTheyLack() {
        loginAs("manager", Role.MODERATOR, EnumSet.of(STAFF_MANAGE, ORDER_VIEW));

        assertCode(() -> service.update("m1", new UpdateStaffRequest(null, List.of("ORDER_VIEW", "REPORT_VIEW"))),
                "PERMISSION_NOT_GRANTABLE");
        // A role change whose defaults include a permission the manager lacks (ORDER_FULFILL) is a grant too.
        assertCode(() -> service.update("m1", new UpdateStaffRequest("PACKER", null)), "PERMISSION_NOT_GRANTABLE");
        verify(userService, never()).changeAccess(any(), any(), any());
    }

    @Test
    void nonOwnerMayRemovePermissionsTheyLack() {
        loginAs("manager", Role.MODERATOR, EnumSet.of(STAFF_MANAGE, ORDER_VIEW));
        service.update("m1", new UpdateStaffRequest(null, List.of("ORDER_VIEW")));
        verify(userService).changeAccess(moderator, Role.MODERATOR, EnumSet.of(ORDER_VIEW));
    }

    @Test
    void memberOfAnotherShopIsNotFound() {
        when(userService.findInShop("other", SHOP)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("other")).isInstanceOf(ResourceNotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "NOT_FOUND");
        assertThatThrownBy(() -> service.update("other", new UpdateStaffRequest("PACKER", null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.changeStatus("other", new StaffStatusRequest("DISABLED")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ==== status ===========================================================================

    @Test
    void disableGoesThroughUserService() {
        StaffResponse response = service.changeStatus("m1", new StaffStatusRequest("DISABLED"));
        verify(userService).changeStatus(moderator, UserStatus.DISABLED);
        assertThat(response.status()).isEqualTo(UserStatus.DISABLED);
    }

    @Test
    void reactivateGoesThroughUserService() {
        moderator.setStatus(UserStatus.DISABLED);
        service.changeStatus("m1", new StaffStatusRequest("ACTIVE"));
        verify(userService).changeStatus(moderator, UserStatus.ACTIVE);
    }

    @Test
    void sameStatusIsANoOp() {
        service.changeStatus("m1", new StaffStatusRequest("ACTIVE"));
        verify(userService, never()).changeStatus(any(), any());
    }

    // ==== read =============================================================================

    @Test
    void getShowsInviterName() {
        moderator.setInvitedBy("owner");
        User owner = member("owner", Role.SHOP_OWNER);
        owner.setName("Rahim");
        when(userService.findInShop("owner", SHOP)).thenReturn(Optional.of(owner));

        StaffResponse response = service.get("m1");
        assertThat(response.invitedBy()).isEqualTo(new StaffResponse.InvitedBy("owner", "Rahim"));
    }

    @Test
    void listPassesShopEscapedSearchAndFilters() {
        when(queryRepository.list(eq(SHOP), any())).thenReturn(new StaffQueryRepository.Result(List.<StaffRow>of(), 0));

        PageResponse<StaffRow> page = service.list("  a.b ", "PACKER", "INVITED", 2, 10);

        ArgumentCaptor<StaffQueryRepository.Filter> filter = ArgumentCaptor.forClass(StaffQueryRepository.Filter.class);
        verify(queryRepository).list(eq(SHOP), filter.capture());
        assertThat(filter.getValue()).isEqualTo(new StaffQueryRepository.Filter("a\\.b", Role.PACKER, "INVITED", NOW, 2, 10));
        assertThat(page.page()).isEqualTo(2);
    }

    @Test
    void catalogHasEveryPermissionWithDescriptionAndStaffRoleDefaults() {
        PermissionCatalogResponse catalog = service.permissionCatalog();

        assertThat(catalog.permissions()).hasSize(Permission.values().length)
                .allSatisfy(p -> assertThat(p.description()).isNotBlank());
        assertThat(catalog.roleDefaults()).containsOnlyKeys(Role.MODERATOR, Role.PACKER, Role.ACCOUNTANT);
        assertThat(catalog.roleDefaults().get(Role.PACKER)).contains(ORDER_FULFILL);
        verifyNoInteractions(queryRepository);
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(AppException.class).hasFieldOrPropertyWithValue("code", code);
    }
}

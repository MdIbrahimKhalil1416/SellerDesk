package com.sellerdesk.staff.service;

import com.sellerdesk.auth.model.User;
import com.sellerdesk.auth.model.UserStatus;
import com.sellerdesk.auth.service.UserService;
import com.sellerdesk.common.config.CacheConfig;
import com.sellerdesk.common.dto.PageResponse;
import com.sellerdesk.common.security.AppUserDetails;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import com.sellerdesk.common.util.Normalize;
import com.sellerdesk.staff.dto.PermissionCatalogResponse;
import com.sellerdesk.staff.dto.StaffResponse;
import com.sellerdesk.staff.dto.StaffRow;
import com.sellerdesk.staff.dto.StaffStatusRequest;
import com.sellerdesk.staff.dto.UpdateStaffRequest;
import com.sellerdesk.staff.repository.StaffQueryRepository;
import java.time.Clock;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Staff members of the shop: list, detail, role/permission changes, deactivate/reactivate
 * (docs/modules/STAFF.md §3.1–3.3). Invitations: {@link InvitationService}.
 *
 * <p>The shop always comes from {@link CurrentUser#shopId()}. A member of another shop is
 * "not found" (404), never "forbidden", so ids cannot be probed.
 *
 * <p>Changes go through {@link UserService}, which bumps {@code tokenVersion} and evicts the auth
 * cache: the member's old tokens stop working at their next request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffService {

    private static final List<Role> STAFF_ROLES = List.of(Role.MODERATOR, Role.PACKER, Role.ACCOUNTANT);

    private final UserService userService;
    private final StaffQueryRepository queryRepository;
    private final CurrentUser currentUser;
    private final Clock clock;

    // ==== Read ===========================================================================

    /**
     * @param role   SHOP_OWNER / MODERATOR / PACKER / ACCOUNTANT or {@code null}
     * @param status ACTIVE / DISABLED / INVITED / EXPIRED or {@code null}
     */
    public PageResponse<StaffRow> list(String q, String role, String status, int page, int size) {
        String shopId = currentUser.shopId();
        String text = Normalize.trimToNull(q);
        StaffQueryRepository.Filter filter = new StaffQueryRepository.Filter(
                text == null ? null : StaffRules.containsRegex(text),
                role == null ? null : Role.valueOf(role),
                status, clock.instant(), page, size);
        StaffQueryRepository.Result result = queryRepository.list(shopId, filter);
        return PageResponse.of(result.content(), page, size, result.totalElements());
    }

    /** Built from the enums; cached because it only changes with a deploy. */
    @Cacheable(cacheNames = CacheConfig.STAFF_PERMISSION_CATALOG, key = "'all'")
    public PermissionCatalogResponse permissionCatalog() {
        List<PermissionCatalogResponse.PermissionInfo> permissions = Arrays.stream(Permission.values())
                .map(p -> new PermissionCatalogResponse.PermissionInfo(p, p.description()))
                .toList();
        Map<Role, List<Permission>> defaults = new LinkedHashMap<>();
        STAFF_ROLES.forEach(r -> defaults.put(r, r.defaultPermissions().stream().sorted().toList()));
        return new PermissionCatalogResponse(permissions, defaults);
    }

    public StaffResponse get(String userId) {
        String shopId = currentUser.shopId();
        return toResponse(load(userId, shopId), shopId);
    }

    // ==== Change ==========================================================================

    /**
     * New role and/or permissions. A new role without a permission list → that role's defaults.
     * Nothing changed → no write and no logout.
     */
    public StaffResponse update(String userId, UpdateStaffRequest request) {
        AppUserDetails editor = currentUser.details();
        String shopId = currentUser.shopId();
        User target = load(userId, shopId);
        StaffRules.checkCanModify(editor, target);

        Role newRole = request.role() == null ? target.getRole() : StaffRules.parseStaffRole(request.role(), "role");
        Set<Permission> current = target.getPermissions();
        Set<Permission> newPermissions;
        if (request.permissions() != null) {
            newPermissions = StaffRules.parsePermissions(request.permissions());
        } else if (newRole != target.getRole()) {
            newPermissions = EnumSet.copyOf(newRole.defaultPermissions());
        } else {
            newPermissions = current;
        }
        StaffRules.checkGrantable(editor, current, newPermissions);

        if (newRole == target.getRole() && newPermissions.equals(current)) {
            return toResponse(target, shopId);
        }
        User saved = userService.changeAccess(target, newRole, newPermissions);
        log.info("Shop {}: user {} changed role/permissions of user {}", shopId, editor.userId(), userId);
        return toResponse(saved, shopId);
    }

    /** ACTIVE ↔ DISABLED. Same status as now → no-op. */
    public StaffResponse changeStatus(String userId, StaffStatusRequest request) {
        AppUserDetails editor = currentUser.details();
        String shopId = currentUser.shopId();
        User target = load(userId, shopId);
        StaffRules.checkCanModify(editor, target);

        UserStatus status = UserStatus.valueOf(request.status());
        if (target.getStatus() == status) {
            return toResponse(target, shopId);
        }
        User saved = userService.changeStatus(target, status);
        log.info("Shop {}: user {} set user {} to {}", shopId, editor.userId(), userId, status);
        return toResponse(saved, shopId);
    }

    // ==== helpers =========================================================================

    private User load(String userId, String shopId) {
        return userService.findInShop(userId, shopId).orElseThrow(StaffErrors::memberNotFound);
    }

    private StaffResponse toResponse(User user, String shopId) {
        StaffResponse.InvitedBy invitedBy = user.getInvitedBy() == null ? null
                : new StaffResponse.InvitedBy(user.getInvitedBy(),
                        userService.findInShop(user.getInvitedBy(), shopId).map(User::getName).orElse(null));
        return StaffResponse.from(user, invitedBy);
    }
}

package com.sellerdesk.staff.dto;

import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import java.util.List;
import java.util.Map;

/** All permissions with descriptions, and each staff role's defaults, for the staff edit UI. */
public record PermissionCatalogResponse(List<PermissionInfo> permissions, Map<Role, List<Permission>> roleDefaults) {

    public record PermissionInfo(Permission code, String description) {
    }
}

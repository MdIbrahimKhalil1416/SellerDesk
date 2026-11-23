package com.sellerdesk.common.security;

/**
 * Every action a shop user can be allowed to do (docs/SECURITY.md §3).
 *
 * <p>Permissions, not roles, are what the code checks:
 * {@code @PreAuthorize("hasAuthority('ORDER_CREATE')")}. The enum name is the authority string,
 * so never rename a constant without migrating the {@code users.permissions} arrays in MongoDB.
 *
 * <p>The description is the "Allows" column of docs/SECURITY.md §3. The staff screen shows it
 * ({@code GET /staff/permissions}), so UI and docs never drift apart.
 */
public enum Permission {
    SHOP_SETTINGS("Edit shop profile and settings"),
    STAFF_MANAGE("Invite, edit, disable staff; change their role and permissions"),
    PRODUCT_VIEW("See products and variants"),
    PRODUCT_EDIT("Create/edit/archive products and variants"),
    STOCK_VIEW("See stock levels and movement log"),
    STOCK_ADJUST("Manual stock in/out"),
    CUSTOMER_VIEW("See customers"),
    CUSTOMER_EDIT("Create/edit customers"),
    ORDER_VIEW("See orders"),
    ORDER_CREATE("Create orders"),
    ORDER_EDIT("Edit orders (items only while PENDING)"),
    ORDER_CONFIRM("PENDING → CONFIRMED"),
    ORDER_FULFILL("CONFIRMED → PACKED → SHIPPED"),
    ORDER_CLOSE("SHIPPED → DELIVERED or RETURNED"),
    ORDER_CANCEL("→ CANCELLED"),
    REPORT_VIEW("Money totals on the dashboard, cost prices");

    private final String description;

    Permission(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}

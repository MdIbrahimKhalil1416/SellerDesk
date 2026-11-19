package com.sellerdesk.common.security;

/**
 * Every action a shop user can be allowed to do (docs/SECURITY.md §3).
 *
 * <p>Permissions, not roles, are what the code checks:
 * {@code @PreAuthorize("hasAuthority('ORDER_CREATE')")}. The enum name is the authority string,
 * so never rename a constant without migrating the {@code users.permissions} arrays in MongoDB.
 */
public enum Permission {
    SHOP_SETTINGS,
    STAFF_MANAGE,
    PRODUCT_VIEW,
    PRODUCT_EDIT,
    STOCK_VIEW,
    STOCK_ADJUST,
    CUSTOMER_VIEW,
    CUSTOMER_EDIT,
    ORDER_VIEW,
    ORDER_CREATE,
    ORDER_EDIT,
    ORDER_CONFIRM,
    ORDER_FULFILL,
    ORDER_CLOSE,
    ORDER_CANCEL,
    REPORT_VIEW
}

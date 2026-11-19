package com.sellerdesk.common.security;

import static com.sellerdesk.common.security.Permission.*;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * A role is a label plus a <b>starting</b> set of permissions (docs/SECURITY.md §3).
 *
 * <p>The permissions a user actually has are stored on the user document, because an owner may
 * add or remove single permissions for one staff member. {@link #defaultPermissions()} is only
 * used when a user is created or their role changes. This is the one place where the defaults live.
 */
public enum Role {

    /** Platform operator. Has no shopId and no shop permissions: every shop endpoint returns 403. */
    PLATFORM_ADMIN(EnumSet.noneOf(Permission.class)),

    /** Always has every shop permission; they cannot be removed. */
    SHOP_OWNER(EnumSet.allOf(Permission.class)),

    MODERATOR(EnumSet.of(
            PRODUCT_VIEW, STOCK_VIEW,
            CUSTOMER_VIEW, CUSTOMER_EDIT,
            ORDER_VIEW, ORDER_CREATE, ORDER_EDIT, ORDER_CONFIRM, ORDER_CLOSE, ORDER_CANCEL)),

    PACKER(EnumSet.of(
            PRODUCT_VIEW, STOCK_VIEW,
            ORDER_VIEW, ORDER_FULFILL)),

    ACCOUNTANT(EnumSet.of(
            PRODUCT_VIEW, STOCK_VIEW,
            CUSTOMER_VIEW,
            ORDER_VIEW, ORDER_CLOSE,
            REPORT_VIEW));

    private final Set<Permission> defaults;

    Role(EnumSet<Permission> defaults) {
        this.defaults = Collections.unmodifiableSet(defaults);
    }

    /** Read-only. Copy it ({@code EnumSet.copyOf(...)}) before changing it. */
    public Set<Permission> defaultPermissions() {
        return defaults;
    }
}

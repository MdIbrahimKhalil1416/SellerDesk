package com.sellerdesk.common.security;

import static com.sellerdesk.common.security.Permission.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/** Locks the role defaults to the table in docs/SECURITY.md §3. */
class RoleTest {

    @Test
    void shopOwnerHasEveryPermission() {
        assertThat(Role.SHOP_OWNER.defaultPermissions()).isEqualTo(EnumSet.allOf(Permission.class));
    }

    @Test
    void platformAdminHasNoShopPermissions() {
        assertThat(Role.PLATFORM_ADMIN.defaultPermissions()).isEmpty();
    }

    @Test
    void moderatorDefaults() {
        assertThat(Role.MODERATOR.defaultPermissions()).containsExactlyInAnyOrder(
                PRODUCT_VIEW, STOCK_VIEW, CUSTOMER_VIEW, CUSTOMER_EDIT,
                ORDER_VIEW, ORDER_CREATE, ORDER_EDIT, ORDER_CONFIRM, ORDER_CLOSE, ORDER_CANCEL);
    }

    @Test
    void packerDefaults() {
        assertThat(Role.PACKER.defaultPermissions()).containsExactlyInAnyOrder(
                PRODUCT_VIEW, STOCK_VIEW, ORDER_VIEW, ORDER_FULFILL);
    }

    @Test
    void accountantDefaults() {
        assertThat(Role.ACCOUNTANT.defaultPermissions()).containsExactlyInAnyOrder(
                PRODUCT_VIEW, STOCK_VIEW, CUSTOMER_VIEW, ORDER_VIEW, ORDER_CLOSE, REPORT_VIEW);
    }

    @Test
    void defaultsCannotBeModified() {
        assertThatThrownBy(() -> Role.PACKER.defaultPermissions().add(STAFF_MANAGE))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

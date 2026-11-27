package com.sellerdesk.shop.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ShopSettingsTest {

    @Test
    void defaultsForNewShops() {
        assertThat(ShopSettings.defaults()).isEqualTo(new ShopSettings(6_000, 12_000, 5, "SD"));
    }

    /** Shops saved before the prefix existed: MongoDB has no field, Spring passes null. */
    @Test
    void missingPrefixOfAnOlderShopBecomesTheDefault() {
        assertThat(new ShopSettings(7_000, 13_000, 2, null).orderNumberPrefix()).isEqualTo("SD");
        assertThat(new ShopSettings(7_000, 13_000, 2, " ").orderNumberPrefix()).isEqualTo("SD");
    }

    @Test
    void givenPrefixIsKept() {
        assertThat(new ShopSettings(0, 0, 0, "RF24").orderNumberPrefix()).isEqualTo("RF24");
    }
}

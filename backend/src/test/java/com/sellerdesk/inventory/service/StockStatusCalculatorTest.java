package com.sellerdesk.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Low-stock rules (docs/modules/INVENTORY.md §1). */
class StockStatusCalculatorTest {

    @Test
    void variantThresholdWinsElseShopDefault() {
        assertThat(StockStatusCalculator.effectiveThreshold(10, 5)).isEqualTo(10);
        assertThat(StockStatusCalculator.effectiveThreshold(0, 5)).isZero();
        assertThat(StockStatusCalculator.effectiveThreshold(null, 5)).isEqualTo(5);
        assertThat(StockStatusCalculator.source(10)).isEqualTo(ThresholdSource.VARIANT);
        assertThat(StockStatusCalculator.source(null)).isEqualTo(ThresholdSource.SHOP);
    }

    @ParameterizedTest(name = "available {0}, threshold {1} -> {2}")
    @CsvSource({
            "0, 5, OUT",
            "-1, 5, OUT",
            "1, 5, LOW",
            "5, 5, LOW",
            "6, 5, IN_STOCK",
            "0, 0, OUT",
            "1, 0, IN_STOCK"
    })
    void statusFromAvailableAndThreshold(int available, int threshold, StockStatus expected) {
        assertThat(StockStatusCalculator.status(available, threshold)).isEqualTo(expected);
    }
}

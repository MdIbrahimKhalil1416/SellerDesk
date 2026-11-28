package com.sellerdesk.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerdesk.common.error.FieldValidationException;
import com.sellerdesk.common.model.DeliveryZone;
import com.sellerdesk.order.model.OrderStatus;
import com.sellerdesk.shop.model.ShopSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The money arithmetic and the transition table, without Spring or a database. */
class OrderRulesTest {

    private static final ShopSettings SETTINGS = new ShopSettings(6_000, 12_000, 5, "SD");

    @Test
    void totalsAreComputedFromLinesDiscountChargeAndAdvance() {
        OrderRules.Totals t = OrderRules.totals(List.of(90_000L, 25_000L), 5_000L, 6_000L, 20_000L);

        assertThat(t.subtotal()).isEqualTo(115_000);
        assertThat(t.discount()).isEqualTo(5_000);
        assertThat(t.deliveryCharge()).isEqualTo(6_000);
        assertThat(t.total()).isEqualTo(116_000);   // 115000 − 5000 + 6000
        assertThat(t.advancePaid()).isEqualTo(20_000);
        assertThat(t.codAmount()).isEqualTo(96_000); // what the courier collects
    }

    @Test
    void missingDiscountAndAdvanceAreZero() {
        OrderRules.Totals t = OrderRules.totals(List.of(1_000L), null, 0, null);
        assertThat(t.total()).isEqualTo(1_000);
        assertThat(t.codAmount()).isEqualTo(1_000);
    }

    @Test
    void discountMayEqualButNotExceedTheSubtotal() {
        assertThat(OrderRules.totals(List.of(1_000L), 1_000L, 6_000L, null).total()).isEqualTo(6_000);
        assertThatThrownBy(() -> OrderRules.totals(List.of(1_000L), 1_001L, 0, null))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFieldErrors().get(0).field()).isEqualTo("discount"));
    }

    @Test
    void advanceMayEqualButNotExceedTheTotal() {
        assertThat(OrderRules.totals(List.of(1_000L), null, 500, 1_500L).codAmount()).isZero();
        assertThatThrownBy(() -> OrderRules.totals(List.of(1_000L), null, 500, 1_501L))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFieldErrors().get(0).field()).isEqualTo("advancePaid"));
    }

    @Test
    void deliveryChargeDefaultsByZoneAndAnExplicitValueWins() {
        assertThat(OrderRules.deliveryCharge(null, DeliveryZone.INSIDE_DHAKA, SETTINGS)).isEqualTo(6_000);
        assertThat(OrderRules.deliveryCharge(null, DeliveryZone.OUTSIDE_DHAKA, SETTINGS)).isEqualTo(12_000);
        assertThat(OrderRules.deliveryCharge(0L, DeliveryZone.OUTSIDE_DHAKA, SETTINGS)).isZero(); // free delivery
    }

    @Test
    void lineTotalIsPriceTimesQuantityAndTheBiggestOrderFitsInALong() {
        assertThat(OrderRules.lineTotal(45_000, 2)).isEqualTo(90_000);
        long biggestLine = OrderRules.lineTotal(OrderRules.MAX_UNIT_PRICE, OrderRules.MAX_QUANTITY);
        List<Long> fifty = java.util.Collections.nCopies(OrderRules.MAX_ITEMS, biggestLine);
        assertThat(OrderRules.totals(fifty, null, OrderRules.MAX_DELIVERY_CHARGE, null).total())
                .isEqualTo(biggestLine * 50 + OrderRules.MAX_DELIVERY_CHARGE);
    }

    @Test
    void searchTextIsAPhoneOrAnOrderNumber() {
        assertThat(OrderRules.parseSearch(null)).isNull();
        assertThat(OrderRules.parseSearch("  ")).isNull();
        for (String number : List.of("SD-1042", "sd-1042", "SD1042", "AB12-1042", "#1042", "1042", " 1042 ")) {
            assertThat(OrderRules.parseSearch(number)).as(number).isEqualTo(new OrderRules.Search(1042L, null));
        }
        for (String phone : List.of("01712345678", "+8801712345678", "01712-345678", "008801712345678")) {
            assertThat(OrderRules.parseSearch(phone)).as(phone).isEqualTo(new OrderRules.Search(null, "01712345678"));
        }
        for (String bad : List.of("Rahim", "SD-", "SD-10a", "1234567890123", "TOOLONGPREFIX-1")) {
            assertThatThrownBy(() -> OrderRules.parseSearch(bad)).as(bad).isInstanceOf(FieldValidationException.class);
        }
    }

    @Test
    void datesAreDhakaDaysAndToIsInclusive() {
        // Dhaka is UTC+6: the day 28 Nov runs from 27 Nov 18:00 UTC to 28 Nov 18:00 UTC.
        assertThat(OrderRules.startOfDay("2026-11-28", "from")).isEqualTo(java.time.Instant.parse("2026-11-27T18:00:00Z"));
        assertThat(OrderRules.endOfDay("2026-11-28", "to")).isEqualTo(java.time.Instant.parse("2026-11-28T18:00:00Z"));
        assertThat(OrderRules.startOfDay(null, "from")).isNull();
        assertThatThrownBy(() -> OrderRules.startOfDay("28/11/2026", "from"))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFieldErrors().get(0).field()).isEqualTo("from"));
    }

    @Test
    void transitionTableMatchesTheDesign() {
        assertThat(OrderAction.CONFIRM.allowedFrom(OrderStatus.PENDING)).isTrue();
        assertThat(OrderAction.PACK.allowedFrom(OrderStatus.CONFIRMED)).isTrue();
        assertThat(OrderAction.SHIP.allowedFrom(OrderStatus.PACKED)).isTrue();
        assertThat(OrderAction.SHIP.allowedFrom(OrderStatus.CONFIRMED)).isFalse(); // no skipping the packing step
        assertThat(OrderAction.DELIVER.allowedFrom(OrderStatus.SHIPPED)).isTrue();
        assertThat(OrderAction.RETURN.allowedFrom(OrderStatus.SHIPPED)).isTrue();
        assertThat(OrderAction.RETURN.allowedFrom(OrderStatus.DELIVERED)).isFalse(); // DELIVERED is final
        assertThat(OrderAction.CANCEL.allowedFrom(OrderStatus.PENDING)).isTrue();
        assertThat(OrderAction.CANCEL.allowedFrom(OrderStatus.PACKED)).isTrue();
        assertThat(OrderAction.CANCEL.allowedFrom(OrderStatus.SHIPPED)).isFalse(); // with the courier: return instead
        for (OrderStatus terminal : List.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED, OrderStatus.RETURNED)) {
            for (OrderAction action : OrderAction.values()) {
                assertThat(action.allowedFrom(terminal)).as(action + " from " + terminal).isFalse();
            }
        }
    }
}

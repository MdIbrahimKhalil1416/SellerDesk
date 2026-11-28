package com.sellerdesk.order.service;

import com.sellerdesk.common.error.FieldValidationException;
import com.sellerdesk.common.model.DeliveryZone;
import com.sellerdesk.shop.model.ShopSettings;
import java.util.List;

/**
 * Limits and the money arithmetic of an order (docs/modules/ORDER.md §2, §5, §9). Pure functions:
 * easy to unit test, no database.
 *
 * <p>All money is {@code long} poisha. The biggest possible order (50 items × 999 × ৳10 lakh) is
 * about 5 × 10^12 poisha, far below {@code Long.MAX_VALUE}, so no sum can overflow.
 */
public final class OrderRules {

    public static final int MAX_ITEMS = 50;
    public static final int MAX_QUANTITY = 999;
    /** ৳10 lakh, same as the product price limit. */
    public static final long MAX_UNIT_PRICE = 100_000_000L;
    /** ৳5,000, same as the shop setting limit. */
    public static final long MAX_DELIVERY_CHARGE = 500_000L;
    /** Upper bound for discount / advance; the real limits (≤ subtotal, ≤ total) are checked here. */
    public static final long MAX_MONEY = 5_000_000_000_000L;
    public static final int MAX_NOTE = 1000;
    public static final int MAX_REASON = 500;
    public static final String UUID_REGEX =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private OrderRules() {
    }

    /** The totals of an order. */
    public record Totals(long subtotal, long discount, long deliveryCharge, long total, long advancePaid,
                         long codAmount) {
    }

    /**
     * {@code subtotal = Σ lineTotal}, {@code total = subtotal − discount + deliveryCharge},
     * {@code codAmount = total − advancePaid}. Never trusts a total from the client.
     *
     * @param lineTotals     {@code unitPrice × quantity} per item
     * @param deliveryCharge the charge to use (already defaulted by zone if the client sent none)
     * @throws FieldValidationException (400) if {@code discount > subtotal} or {@code advancePaid > total}
     */
    public static Totals totals(List<Long> lineTotals, Long discount, long deliveryCharge, Long advancePaid) {
        long subtotal = 0;
        for (long line : lineTotals) {
            subtotal = Math.addExact(subtotal, line);
        }
        long d = discount == null ? 0 : discount;
        if (d > subtotal) {
            throw new FieldValidationException("discount", "must not be more than the subtotal (" + subtotal + ")");
        }
        long total = subtotal - d + deliveryCharge;
        long advance = advancePaid == null ? 0 : advancePaid;
        if (advance > total) {
            throw new FieldValidationException("advancePaid", "must not be more than the total (" + total + ")");
        }
        return new Totals(subtotal, d, deliveryCharge, total, advance, total - advance);
    }

    /** {@code unitPrice × quantity}. */
    public static long lineTotal(long unitPrice, int quantity) {
        return Math.multiplyExact(unitPrice, quantity);
    }

    /** The charge the client chose, or the shop default for the zone (docs/modules/ORDER.md §5). */
    public static long deliveryCharge(Long requested, DeliveryZone zone, ShopSettings settings) {
        if (requested != null) {
            return requested;
        }
        return zone == DeliveryZone.INSIDE_DHAKA ? settings.deliveryChargeInsideDhaka()
                : settings.deliveryChargeOutsideDhaka();
    }

    /** Trimmed, or {@code null} if blank. */
    public static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

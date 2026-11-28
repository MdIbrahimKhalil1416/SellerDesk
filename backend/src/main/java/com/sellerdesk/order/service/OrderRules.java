package com.sellerdesk.order.service;

import com.sellerdesk.common.error.FieldValidationException;
import com.sellerdesk.common.model.DeliveryZone;
import com.sellerdesk.common.phone.BdPhone;
import com.sellerdesk.shop.model.ShopSettings;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /** Orders per bulk status change. */
    public static final int MAX_BULK = 50;
    /** Sellers think in Bangladesh days: "today's orders" starts at 00:00 in Dhaka, not UTC. */
    public static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Dhaka");
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

    // ==== List search ======================================================================

    /** {@code SD-1042}, {@code sd1042}, {@code #1042}, {@code 1042}: optional prefix, then the number. */
    private static final Pattern ORDER_NUMBER = Pattern.compile("^(?:[A-Za-z0-9]{2,6}-|[A-Za-z]{1,6}|#)?(\\d{1,12})$");

    /** What the list's {@code q} searches for: exactly one of the two is set. */
    public record Search(Long orderNumber, String phone) {
    }

    /**
     * Parses the list's {@code q} (docs/modules/ORDER.md §8): a Bangladeshi mobile number in any
     * accepted format → exact customer phone; else an order number (the prefix is ignored: the
     * number alone is unique per shop, and old orders may carry an old prefix). Checked in this
     * order because a valid phone (11 digits starting {@code 01}) would also look like a number.
     *
     * @return {@code null} for a blank {@code q}
     * @throws FieldValidationException (400 on {@code q}) for anything else, e.g. a name
     */
    public static Search parseSearch(String q) {
        String text = text(q);
        if (text == null) {
            return null;
        }
        Optional<String> phone = BdPhone.tryNormalize(text);
        if (phone.isPresent()) {
            return new Search(null, phone.get());
        }
        Matcher m = ORDER_NUMBER.matcher(text.replace(" ", ""));
        if (m.matches()) {
            return new Search(Long.parseLong(m.group(1)), null);
        }
        throw new FieldValidationException("q", "must be an order number (SD-1042 or 1042) or a phone number");
    }

    /** Start of the Dhaka day {@code date} ({@code YYYY-MM-DD}), or {@code null} if blank. */
    public static Instant startOfDay(String date, String field) {
        LocalDate day = parseDate(date, field);
        return day == null ? null : day.atStartOfDay(SHOP_ZONE).toInstant();
    }

    /** Start of the Dhaka day <b>after</b> {@code date}: makes an inclusive "to" day exclusive. */
    public static Instant endOfDay(String date, String field) {
        LocalDate day = parseDate(date, field);
        return day == null ? null : day.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant();
    }

    private static LocalDate parseDate(String date, String field) {
        String text = text(date);
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new FieldValidationException(field, "must be a date like 2026-11-28");
        }
    }

    /** Trimmed, or {@code null} if blank. */
    public static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

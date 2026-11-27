package com.sellerdesk.common.phone;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bangladeshi mobile numbers: one place that decides what is valid and how it is stored
 * (docs/SCHEMA.md §1). Used for the shop phone, and later for customer phones.
 *
 * <p>Why normalize: a customer typed as {@code +8801712345678} and as {@code 01712345678} is the
 * same person. If both spellings were stored, a unique index on the phone would not see the
 * duplicate and a phone search would miss one of them. So every phone is stored in <b>one</b>
 * format: the 11-digit local form {@code 01XXXXXXXXX}.
 *
 * <p>Accepted input (after removing spaces, dashes, dots and parentheses, and turning Bangla
 * digits ০-৯ into 0-9):
 * <ul>
 *   <li>{@code 01712345678}: local form</li>
 *   <li>{@code +8801712345678}, {@code 8801712345678}, {@code 008801712345678}: with country code 880</li>
 * </ul>
 * The operator digit after {@code 01} must be 3-9 (013-019: Grameenphone, Robi, Airtel, Banglalink,
 * Teletalk). Landlines are not accepted: couriers and customers use mobiles.
 */
public final class BdPhone {

    /** Shown in error messages and API docs. */
    public static final String EXAMPLE = "01712345678";

    /** Default validation message, also used by {@link BdMobile}. */
    public static final String INVALID_MESSAGE =
            "must be a Bangladeshi mobile number like 01712345678 (a +880 or 880 prefix is allowed)";

    /** Characters people type for readability; they carry no meaning. */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-().]");

    /** Country code (+880, 880, 00880) or the leading 0, then 1, the operator digit 3-9, and 8 digits. */
    private static final Pattern MOBILE = Pattern.compile("^(?:\\+880|880|00880|0)(1[3-9]\\d{8})$");

    private BdPhone() {
    }

    /**
     * The stored form {@code 01XXXXXXXXX}, or empty if {@code input} is not a Bangladeshi mobile
     * number. {@code null} and blank give empty.
     */
    public static Optional<String> tryNormalize(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String compact = SEPARATORS.matcher(toAsciiDigits(input.strip())).replaceAll("");
        Matcher m = MOBILE.matcher(compact);
        return m.matches() ? Optional.of("0" + m.group(1)) : Optional.empty();
    }

    /**
     * Like {@link #tryNormalize}, for input that was already validated (e.g. with {@link BdMobile}).
     * {@code null} stays {@code null}.
     *
     * @throws IllegalArgumentException if it is not a Bangladeshi mobile number
     */
    public static String normalize(String input) {
        if (input == null) {
            return null;
        }
        return tryNormalize(input).orElseThrow(() -> new IllegalArgumentException("Not a Bangladeshi mobile number"));
    }

    /** {@code true} if {@code input} is a Bangladeshi mobile number in any accepted format. */
    public static boolean isValid(String input) {
        return tryNormalize(input).isPresent();
    }

    /** Bangla digits ০১২৩৪৫৬৭৮৯ (U+09E6–U+09EF) → 0-9. Phones typed on a Bangla keyboard use them. */
    private static String toAsciiDigits(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out.append(c >= '০' && c <= '৯' ? (char) ('0' + (c - '০')) : c);
        }
        return out.toString();
    }
}

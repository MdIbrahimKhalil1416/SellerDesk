package com.sellerdesk.common.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Brings values into the one format we store (docs/SCHEMA.md §1). Unique indexes only work if
 * every copy of a value is written the same way: {@code +8801712345678} and {@code 01712345678}
 * are the same phone.
 */
public final class Normalize {

    /** What request DTOs accept: optional {@code +88} / {@code 88} prefix, then a BD mobile number. */
    public static final String BD_MOBILE_INPUT_REGEX = "^(\\+?88)?01[3-9]\\d{8}$";

    private static final Pattern BD_MOBILE_INPUT = Pattern.compile(BD_MOBILE_INPUT_REGEX);

    private Normalize() {
    }

    /** Trimmed and lowercase. {@code null} stays {@code null}. */
    public static String email(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * {@code +8801712345678}, {@code 8801712345678}, {@code 01712345678} → {@code 01712345678}.
     * Spaces and dashes are removed first.
     *
     * @throws IllegalArgumentException if it is not a Bangladeshi mobile number
     */
    public static String bdMobile(String phone) {
        if (phone == null) {
            return null;
        }
        String compact = phone.replaceAll("[\\s-]", "");
        if (!BD_MOBILE_INPUT.matcher(compact).matches()) {
            throw new IllegalArgumentException("Not a Bangladeshi mobile number");
        }
        return compact.substring(compact.length() - 11);
    }

    /** Trimmed; blank becomes {@code null}. */
    public static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

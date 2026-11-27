package com.sellerdesk.common.util;

import java.util.Locale;

/**
 * Brings values into the one format we store (docs/SCHEMA.md §1). Unique indexes only work if
 * every copy of a value is written the same way: {@code +8801712345678} and {@code 01712345678}
 * are the same phone. Phones: {@link com.sellerdesk.common.phone.BdPhone}.
 */
public final class Normalize {

    private Normalize() {
    }

    /** Trimmed and lowercase. {@code null} stays {@code null}. */
    public static String email(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Trimmed; blank becomes {@code null}. */
    public static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

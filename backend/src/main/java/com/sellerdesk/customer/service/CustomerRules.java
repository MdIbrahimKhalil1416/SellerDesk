package com.sellerdesk.customer.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Limits and normalization of the Customer module (docs/modules/CUSTOMER.md §2–§4). Pure functions.
 * Phones themselves are normalized by {@code common.phone.BdPhone}.
 */
public final class CustomerRules {

    public static final int MAX_NAME = 100;
    public static final int MAX_NOTE = 1000;
    public static final int MAX_TAGS = 10;
    public static final int MAX_TAG = 30;
    public static final int MAX_LABEL = 30;
    public static final int MAX_ADDRESSES = 5;
    public static final String URL_REGEX = "^https?://\\S+$";

    /** Phone search needs at least this many digits; "ends with" needs one more. */
    static final int MIN_PHONE_PREFIX = 3;
    static final int MIN_PHONE_SUFFIX = 4;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s\\-().]");
    private static final Pattern COUNTRY_CODE = Pattern.compile("^(?:\\+880|00880|880)");
    private static final Pattern DIGITS = Pattern.compile("^\\d+$");
    private static final Pattern REGEX_SPECIAL = Pattern.compile("[\\\\^$.|?*+()\\[\\]{}]");

    private CustomerRules() {
    }

    /** Trimmed, inner whitespace collapsed; blank → {@code null}. */
    public static String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return WHITESPACE.matcher(value.trim()).replaceAll(" ");
    }

    public static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    /** "01712345678" → "87654321710". */
    public static String reverse(String value) {
        return new StringBuilder(value).reverse().toString();
    }

    /** Tags as typed (trimmed, single spaces), first spelling wins for duplicates in any case. */
    public static List<String> normalizeTags(List<String> tags) {
        Map<String, String> byKey = new LinkedHashMap<>();
        if (tags != null) {
            for (String tag : tags) {
                String t = normalizeText(tag);
                if (t != null) {
                    byKey.putIfAbsent(lower(t), t);
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    public static List<String> tagKeys(List<String> normalizedTags) {
        return normalizedTags.stream().map(CustomerRules::lower).toList();
    }

    /** {@code [phone, altPhone?]}. */
    public static List<String> phoneKeys(String phone, String altPhone) {
        return altPhone == null ? List.of(phone) : List.of(phone, altPhone);
    }

    public static List<String> phoneKeysReversed(String phone, String altPhone) {
        return phoneKeys(phone, altPhone).stream().map(CustomerRules::reverse).toList();
    }

    /** {@code ^<escaped text>} for a prefix match that can use an index. */
    public static String startsWithRegex(String text) {
        return "^" + REGEX_SPECIAL.matcher(text).replaceAll("\\\\$0");
    }

    /**
     * How {@code q} of the customer list is searched. {@code phoneDigits != null}: phone search
     * (prefix, and "ends with" when {@code suffix}); otherwise {@code nameLower} prefix.
     */
    public record SearchText(String phoneDigits, boolean suffix, String nameLower) {
    }

    /**
     * Phone-looking input is normalized like a phone: separators removed, Bangla digits → 0–9, a
     * leading {@code +880} / {@code 00880} / {@code 880} → {@code 0}. If only digits remain (3+),
     * it is a phone search; otherwise a name search. {@code null} for blank input.
     */
    public static SearchText parseSearch(String q) {
        String text = normalizeText(q);
        if (text == null) {
            return null;
        }
        String compact = PHONE_SEPARATORS.matcher(asciiDigits(text)).replaceAll("");
        compact = COUNTRY_CODE.matcher(compact).replaceFirst("0");
        if (DIGITS.matcher(compact).matches() && compact.length() >= MIN_PHONE_PREFIX) {
            return new SearchText(compact, compact.length() >= MIN_PHONE_SUFFIX, null);
        }
        return new SearchText(null, false, lower(text));
    }

    /** Bangla digits ০-৯ → 0-9 (same rule as {@code BdPhone}). */
    static String asciiDigits(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out.append(c >= '০' && c <= '৯' ? (char) ('0' + (c - '০')) : c);
        }
        return out.toString();
    }

    /** Two addresses are "the same place" (find-or-create does not add it twice). */
    public static String addressSignature(String line, String area, String district, Object zone) {
        return String.join("|", lowerOrEmpty(normalizeText(line)), lowerOrEmpty(normalizeText(area)),
                lowerOrEmpty(normalizeText(district)), String.valueOf(zone));
    }

    private static String lowerOrEmpty(String s) {
        return s == null ? "" : lower(s);
    }
}

package com.sellerdesk.product.service;

import com.sellerdesk.product.model.Variant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Limits and normalization rules of the product module (docs/modules/PRODUCT.md §1–2), in one
 * place so DTO annotations, services and tests use the same numbers.
 *
 * <p>Normalizing means "bring every value into the one form we store", so that e.g. {@code " tshirt-m "}
 * and {@code "TSHIRT-M"} are recognized as the same SKU by the unique index.
 */
public final class ProductRules {

    public static final int MAX_VARIANTS = 100;
    public static final int MAX_IMAGES = 8;
    public static final int MAX_ATTRIBUTES = 5;
    public static final int MAX_VARIANT_NAME = 100;
    /** ৳10 lakh in poisha. */
    public static final long MAX_MONEY = 100_000_000L;
    public static final int MAX_LOW_STOCK_THRESHOLD = 100_000;

    /**
     * What a request may send. Lowercase and surrounding spaces are fine; the service then trims
     * and uppercases to the stored form {@code ^[A-Z0-9][A-Z0-9._-]{0,39}$}.
     */
    public static final String SKU_INPUT_REGEX = "^\\s*[A-Za-z0-9][A-Za-z0-9._-]{0,39}\\s*$";
    public static final String SKU_MESSAGE =
            "must be 1-40 letters, digits, '.', '_' or '-', starting with a letter or digit";

    /** MongoDB map keys must not contain '.' or '$' (they would mean a nested path / operator). */
    public static final String ATTRIBUTE_KEY_REGEX = "^[^.$]*$";

    static final String DEFAULT_VARIANT_NAME = "Default";

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern REGEX_SPECIAL = Pattern.compile("[\\\\^$.|?*+()\\[\\]{}]");

    private ProductRules() {
    }

    /** {@code " tshirt-m "} → {@code "TSHIRT-M"}. */
    public static String normalizeSku(String sku) {
        return sku.trim().toUpperCase(Locale.ROOT);
    }

    /** Trimmed, runs of whitespace → one space; blank → {@code null}. Used for category and names. */
    public static String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return WHITESPACE.matcher(value.trim()).replaceAll(" ");
    }

    /** Lowercase form used for matching ({@code nameLower}, {@code categoryKey}). */
    public static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    /** Trims keys and values, keeps the order the user typed. {@code null} → empty map. */
    public static Map<String, String> normalizeAttributes(Map<String, String> attributes) {
        Map<String, String> result = new LinkedHashMap<>();
        if (attributes != null) {
            attributes.forEach((key, value) -> result.put(normalizeText(key), normalizeText(value)));
        }
        return result;
    }

    /** {@code {Color=Red, Size=M}} → {@code "Red / M"}; no attributes → {@code "Default"}. */
    public static String defaultVariantName(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return DEFAULT_VARIANT_NAME;
        }
        return String.join(" / ", attributes.values());
    }

    /**
     * A comparable form of an attribute combination: case-insensitive, order-independent.
     * {@code {Size=M, Color=Red}} and {@code {color=red, size=m}} give the same value.
     * Variants without attributes give {@code null}: they are told apart by name instead.
     */
    public static String attributeSignature(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        Map<String, String> sorted = new TreeMap<>();
        attributes.forEach((key, value) -> sorted.put(lower(key), lower(value)));
        return sorted.toString();
    }

    /** Escapes regex characters so user input can be used in a "starts with" query. */
    public static String startsWithRegex(String text) {
        return "^" + REGEX_SPECIAL.matcher(text).replaceAll("\\\\$0");
    }

    /** Lowest and highest price of the <b>active</b> variants; {@code {null, null}} if none is active. */
    public static Long[] priceRange(List<Variant> variants) {
        Long min = null;
        Long max = null;
        for (Variant v : variants) {
            if (v.isActive()) {
                min = min == null ? v.getPrice() : Math.min(min, v.getPrice());
                max = max == null ? v.getPrice() : Math.max(max, v.getPrice());
            }
        }
        return new Long[] {min, max};
    }
}

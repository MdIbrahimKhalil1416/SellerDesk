package com.sellerdesk.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerdesk.product.model.Variant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductRulesTest {

    @Test
    void skuIsTrimmedAndUppercased() {
        assertThat(ProductRules.normalizeSku("  tshirt-red.m_1 ")).isEqualTo("TSHIRT-RED.M_1");
    }

    @Test
    void skuInputPatternAcceptsLowercaseButRejectsBadCharacters() {
        assertThat(" tshirt-m ").matches(ProductRules.SKU_INPUT_REGEX);
        assertThat("A".repeat(40)).matches(ProductRules.SKU_INPUT_REGEX);
        assertThat("A".repeat(41)).doesNotMatch(ProductRules.SKU_INPUT_REGEX);
        assertThat("-ABC").doesNotMatch(ProductRules.SKU_INPUT_REGEX);
        assertThat("AB C").doesNotMatch(ProductRules.SKU_INPUT_REGEX);
        assertThat("AB/C").doesNotMatch(ProductRules.SKU_INPUT_REGEX);
    }

    @Test
    void textIsTrimmedWithSingleSpacesAndBlankBecomesNull() {
        assertThat(ProductRules.normalizeText("  Men's   T-Shirts ")).isEqualTo("Men's T-Shirts");
        assertThat(ProductRules.normalizeText("   ")).isNull();
        assertThat(ProductRules.normalizeText(null)).isNull();
    }

    @Test
    void defaultVariantNameJoinsAttributeValuesInOrder() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("Color", "Red");
        attributes.put("Size", "M");
        assertThat(ProductRules.defaultVariantName(attributes)).isEqualTo("Red / M");
        assertThat(ProductRules.defaultVariantName(Map.of())).isEqualTo("Default");
    }

    @Test
    void attributeSignatureIgnoresCaseAndOrder() {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("Size", "M");
        a.put("Color", "Red");
        Map<String, String> b = new LinkedHashMap<>();
        b.put("color", "RED");
        b.put("size", "m");
        assertThat(ProductRules.attributeSignature(a)).isEqualTo(ProductRules.attributeSignature(b));
        assertThat(ProductRules.attributeSignature(Map.of())).isNull();
    }

    @Test
    void attributesAreTrimmed() {
        assertThat(ProductRules.normalizeAttributes(Map.of(" Color ", "  Dark   Red "))).containsEntry("Color", "Dark Red");
        assertThat(ProductRules.normalizeAttributes(null)).isEmpty();
    }

    @Test
    void startsWithRegexEscapesSpecialCharacters() {
        assertThat(ProductRules.startsWithRegex("a.b*(c)")).isEqualTo("^a\\.b\\*\\(c\\)");
        assertThat("a.b*(c) shirt").containsPattern(ProductRules.startsWithRegex("a.b*(c)"));
        assertThat("axb shirt").doesNotContainPattern(ProductRules.startsWithRegex("a.b"));
    }

    @Test
    void priceRangeUsesOnlyActiveVariants() {
        assertThat(ProductRules.priceRange(List.of(variant(500, true), variant(100, false), variant(900, true))))
                .containsExactly(500L, 900L);
        assertThat(ProductRules.priceRange(List.of(variant(100, false)))).containsExactly(null, null);
    }

    static Variant variant(long price, boolean active) {
        Variant v = new Variant();
        v.setPrice(price);
        v.setActive(active);
        return v;
    }
}

package com.sellerdesk.customer.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Normalization and search parsing of the Customer module (docs/modules/CUSTOMER.md §3–§4). */
class CustomerRulesTest {

    @ParameterizedTest(name = "\"{0}\" -> phone digits {1}")
    @CsvSource(delimiter = '|', value = {
            "01712345678|01712345678",
            "+8801712345678|01712345678",
            "8801712345678|01712345678",
            "008801712345678|01712345678",
            "01712-345 678|01712345678",
            "(017) 1234.5678|01712345678",
            "০১৭১২৩৪৫৬৭৮|01712345678",
            "0171|0171",
            "5678|5678",
            "+880 17|017"
    })
    void digitsAreAPhoneSearch(String q, String digits) {
        CustomerRules.SearchText search = CustomerRules.parseSearch(q);
        assertThat(search.phoneDigits()).isEqualTo(digits);
        assertThat(search.nameLower()).isNull();
    }

    @Test
    void endsWithNeedsFourDigits() {
        assertThat(CustomerRules.parseSearch("567").suffix()).isFalse();
        assertThat(CustomerRules.parseSearch("5678").suffix()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Rahima", "  rahima  Begum ", "01ab", "12"})
    void textOrTooFewDigitsIsANameSearch(String q) {
        CustomerRules.SearchText search = CustomerRules.parseSearch(q);
        assertThat(search.phoneDigits()).isNull();
        assertThat(search.nameLower()).isEqualTo(CustomerRules.lower(CustomerRules.normalizeText(q)));
    }

    @Test
    void blankSearchIsNoSearch() {
        assertThat(CustomerRules.parseSearch("   ")).isNull();
        assertThat(CustomerRules.parseSearch(null)).isNull();
    }

    @Test
    void tagsAreTrimmedAndDeduplicatedIgnoringCaseFirstSpellingWins() {
        List<String> tags = CustomerRules.normalizeTags(Arrays.asList(" VIP ", "vip", "Wholesale  buyer", "", null, "Vip"));
        assertThat(tags).containsExactly("VIP", "Wholesale buyer");
        assertThat(CustomerRules.tagKeys(tags)).containsExactly("vip", "wholesale buyer");
    }

    @Test
    void phoneKeysAndReversedKeys() {
        assertThat(CustomerRules.phoneKeys("01712345678", null)).containsExactly("01712345678");
        assertThat(CustomerRules.phoneKeysReversed("01712345678", "01812345678"))
                .containsExactly("87654321710", "87654321810");
    }

    @Test
    void startsWithRegexEscapesSpecialCharacters() {
        assertThat(CustomerRules.startsWithRegex("a.b(c")).isEqualTo("^a\\.b\\(c");
    }

    @Test
    void sameAddressIgnoresCaseAndSpaces() {
        assertThat(CustomerRules.addressSignature(" House 5,  Road 3 ", "Mirpur", "dhaka", "INSIDE_DHAKA"))
                .isEqualTo(CustomerRules.addressSignature("house 5, road 3", "MIRPUR", "Dhaka", "INSIDE_DHAKA"));
        assertThat(CustomerRules.addressSignature("House 5", null, "Dhaka", "INSIDE_DHAKA"))
                .isNotEqualTo(CustomerRules.addressSignature("House 5", null, "Dhaka", "OUTSIDE_DHAKA"));
    }
}

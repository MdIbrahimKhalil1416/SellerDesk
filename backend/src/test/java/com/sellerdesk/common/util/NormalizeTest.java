package com.sellerdesk.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NormalizeTest {

    @ParameterizedTest
    @ValueSource(strings = {"01712345678", "+8801712345678", "8801712345678", "017-1234 5678"})
    void bdMobileFormatsAllBecomeElevenDigits(String input) {
        assertThat(Normalize.bdMobile(input)).isEqualTo("01712345678");
    }

    @ParameterizedTest
    @ValueSource(strings = {"01212345678", "0171234567", "+4917612345678", "abc"})
    void nonBangladeshiMobileIsRejected(String input) {
        assertThatThrownBy(() -> Normalize.bdMobile(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emailIsTrimmedAndLowercased() {
        assertThat(Normalize.email("  Rahim@Example.COM ")).isEqualTo("rahim@example.com");
        assertThat(Normalize.email(null)).isNull();
    }
}

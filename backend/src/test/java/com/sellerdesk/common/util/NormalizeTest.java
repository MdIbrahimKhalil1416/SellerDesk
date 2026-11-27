package com.sellerdesk.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NormalizeTest {

    @Test
    void emailIsTrimmedAndLowercased() {
        assertThat(Normalize.email("  Rahim@Example.COM ")).isEqualTo("rahim@example.com");
        assertThat(Normalize.email(null)).isNull();
    }

    @Test
    void trimToNullTurnsBlankIntoNull() {
        assertThat(Normalize.trimToNull("  x ")).isEqualTo("x");
        assertThat(Normalize.trimToNull("   ")).isNull();
        assertThat(Normalize.trimToNull(null)).isNull();
    }
}

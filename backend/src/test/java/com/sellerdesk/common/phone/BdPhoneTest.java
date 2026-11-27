package com.sellerdesk.common.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BdPhoneTest {

    @ParameterizedTest(name = "\"{0}\" -> 01712345678")
    @ValueSource(strings = {
            "01712345678",          // local
            "+8801712345678",       // international with +
            "8801712345678",        // international without +
            "008801712345678",      // international with 00
            "017-1234-5678",        // dashes
            "017 1234 5678",        // spaces
            "+880 1712-345678",     // mixed
            "+880 (1712) 345.678",  // parentheses and dots
            "  01712345678  ",      // surrounding spaces
            "\t01712345678\n",      // tabs and newlines (pasted)
            "০১৭১২৩৪৫৬৭৮",           // Bangla digits
            "+৮৮০১৭১২৩৪৫৬৭৮",        // Bangla digits with country code
    })
    void everyAcceptedFormatBecomesTheLocalElevenDigitForm(String input) {
        assertThat(BdPhone.normalize(input)).isEqualTo("01712345678");
        assertThat(BdPhone.isValid(input)).isTrue();
    }

    @ParameterizedTest(name = "operator 01{0}")
    @ValueSource(strings = {"3", "4", "5", "6", "7", "8", "9"})
    void everyOperatorDigitFromThreeToNineIsAccepted(String operatorDigit) {
        String phone = "01" + operatorDigit + "12345678";
        assertThat(BdPhone.normalize("+880" + phone.substring(1))).isEqualTo(phone);
    }

    @ParameterizedTest(name = "\"{0}\" is rejected")
    @ValueSource(strings = {
            "01212345678",       // operator 2 does not exist
            "01112345678",       // operator 1 retired
            "0171234567",        // 10 digits
            "017123456789",      // 12 digits
            "1712345678",        // no leading 0 and no country code
            "+881712345678",     // +88 without the 0
            "+8817123456789",    // wrong country code layout
            "+8800171234567",    // +880 followed by the local 0
            "+4917612345678",    // German mobile
            "0291234567",        // Dhaka landline
            "abc",
            "017123x5678",       // letter inside
            "+",
            "++8801712345678",
            "01712345678 ext 5",
    })
    void nonBangladeshiOrMalformedNumbersAreRejected(String input) {
        assertThat(BdPhone.tryNormalize(input)).isEmpty();
        assertThat(BdPhone.isValid(input)).isFalse();
        assertThatThrownBy(() -> BdPhone.normalize(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void nullAndBlankAreNotValid(String input) {
        assertThat(BdPhone.isValid(input)).isFalse();
        assertThat(BdPhone.tryNormalize(input)).isEmpty();
    }

    @Test
    void normalizeKeepsNull() {
        assertThat(BdPhone.normalize(null)).isNull();
    }

    @Test
    void normalizingTwiceGivesTheSameResult() {
        String once = BdPhone.normalize("+880 1812-345678");
        assertThat(BdPhone.normalize(once)).isEqualTo(once).isEqualTo("01812345678");
    }

    /** The {@link BdMobile} annotation, through a real Bean Validation validator. */
    @Nested
    class Annotation {

        private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        record OptionalPhone(@BdMobile String phone) {
        }

        record RequiredPhone(@NotBlank @BdMobile String phone) {
        }

        @Test
        void validFormatsPass() {
            assertThat(validator.validate(new OptionalPhone("+8801712345678"))).isEmpty();
            assertThat(validator.validate(new OptionalPhone("017-1234 5678"))).isEmpty();
        }

        @Test
        void invalidNumberGetsTheClearMessage() {
            assertThat(validator.validate(new OptionalPhone("01212345678")))
                    .singleElement()
                    .satisfies(v -> assertThat(v.getMessage()).isEqualTo(BdPhone.INVALID_MESSAGE));
        }

        @Test
        void nullIsLeftToNotBlank() {
            assertThat(validator.validate(new OptionalPhone(null))).isEmpty();
            assertThat(validator.validate(new RequiredPhone(null))).singleElement()
                    .satisfies(v -> assertThat(v.getConstraintDescriptor().getAnnotation()).isInstanceOf(NotBlank.class));
        }

        @Test
        void blankGetsOnlyOneError() {
            assertThat(validator.validate(new RequiredPhone("  "))).singleElement()
                    .satisfies(v -> assertThat(v.getConstraintDescriptor().getAnnotation()).isInstanceOf(NotBlank.class));
        }
    }
}

package com.sellerdesk.shop.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerdesk.common.dto.AddressDto;
import com.sellerdesk.common.model.DeliveryZone;
import com.sellerdesk.common.phone.BdPhone;
import com.sellerdesk.shop.service.ShopRules;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The Bean Validation rules of {@code PUT /shop}, without Spring (fast). */
class UpdateShopRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static UpdateShopRequest.Settings settings(Long inside, Long outside, Integer threshold, String prefix) {
        return new UpdateShopRequest.Settings(inside, outside, threshold, prefix);
    }

    private static UpdateShopRequest request(String name, String phone, UpdateShopRequest.Settings settings) {
        return new UpdateShopRequest(name, phone, "hello@rahim.com",
                new AddressDto("House 1", null, "Dhaka", DeliveryZone.INSIDE_DHAKA, null),
                "https://facebook.com/rahim", settings);
    }

    private static UpdateShopRequest valid() {
        return request("Rahim Fashion", "01712345678", settings(6_000L, 12_000L, 5, "SD"));
    }

    /** Field path → messages, e.g. {@code settings.orderNumberPrefix}. */
    private Set<String> invalidFields(UpdateShopRequest request) {
        return validator.validate(request).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    @Test
    void validRequestHasNoErrors() {
        assertThat(validator.validate(valid())).isEmpty();
    }

    @Test
    void optionalFieldsMayBeMissing() {
        UpdateShopRequest minimal = new UpdateShopRequest("Rahim Fashion", "01712345678", null, null, null,
                settings(0L, 0L, 0, "SD"));
        assertThat(validator.validate(minimal)).isEmpty();
    }

    // ---- profile ----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "R"})
    void nameMustHaveTwoToHundredCharacters(String name) {
        assertThat(invalidFields(request(name, "01712345678", valid().settings()))).contains("name");
        assertThat(invalidFields(request("x".repeat(101), "01712345678", valid().settings()))).contains("name");
        assertThat(invalidFields(request("x".repeat(100), "01712345678", valid().settings()))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"01712345678", "+8801712345678", "8801712345678", "017-1234 5678"})
    void everyBangladeshiPhoneFormatIsAccepted(String phone) {
        assertThat(invalidFields(request("Rahim", phone, valid().settings()))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"01212345678", "12345", "+4917612345678"})
    void otherPhonesGetTheClearPhoneMessage(String phone) {
        Set<ConstraintViolation<UpdateShopRequest>> violations = validator.validate(request("Rahim", phone, valid().settings()));
        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.getPropertyPath()).hasToString("phone");
            assertThat(v.getMessage()).isEqualTo(BdPhone.INVALID_MESSAGE);
        });
    }

    @Test
    void phoneIsRequired() {
        assertThat(invalidFields(request("Rahim", null, valid().settings()))).containsExactly("phone");
        assertThat(validator.validate(request("Rahim", "", valid().settings()))).hasSize(1);
    }

    @Test
    void badEmailUrlAndAddressAreRejected() {
        UpdateShopRequest bad = new UpdateShopRequest("Rahim", "01712345678", "not-an-email",
                new AddressDto("", null, "Dhaka", null, null), "facebook.com/rahim", valid().settings());
        assertThat(invalidFields(bad))
                .containsExactlyInAnyOrder("email", "address.line", "address.zone", "facebookPageUrl");
    }

    // ---- settings ---------------------------------------------------------------------

    @Test
    void settingsAreRequiredAndEachFieldMustBeGiven() {
        assertThat(invalidFields(request("Rahim", "01712345678", null))).containsExactly("settings");
        assertThat(invalidFields(request("Rahim", "01712345678", settings(null, null, null, null))))
                .containsExactlyInAnyOrder("settings.deliveryChargeInsideDhaka", "settings.deliveryChargeOutsideDhaka",
                        "settings.defaultLowStockThreshold", "settings.orderNumberPrefix");
    }

    @Test
    void deliveryChargesMustBeBetweenZeroAndTheMaximum() {
        long max = ShopRules.MAX_DELIVERY_CHARGE;
        assertThat(invalidFields(request("R1", "01712345678", settings(0L, max, 5, "SD")))).isEmpty();
        assertThat(invalidFields(request("R1", "01712345678", settings(-1L, max + 1, 5, "SD"))))
                .containsExactlyInAnyOrder("settings.deliveryChargeInsideDhaka", "settings.deliveryChargeOutsideDhaka");
    }

    @Test
    void lowStockThresholdMustBeZeroOrMoreAndAtMostTheMaximum() {
        assertThat(invalidFields(request("R1", "01712345678", settings(0L, 0L, 0, "SD")))).isEmpty();
        assertThat(invalidFields(request("R1", "01712345678", settings(0L, 0L, ShopRules.MAX_LOW_STOCK_THRESHOLD, "SD"))))
                .isEmpty();
        assertThat(invalidFields(request("R1", "01712345678", settings(0L, 0L, -1, "SD"))))
                .containsExactly("settings.defaultLowStockThreshold");
        assertThat(invalidFields(request("R1", "01712345678", settings(0L, 0L, ShopRules.MAX_LOW_STOCK_THRESHOLD + 1, "SD"))))
                .containsExactly("settings.defaultLowStockThreshold");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SD", "RF24", "ABCDEF", "123456", "rf"})
    void orderNumberPrefixOfTwoToSixLettersOrDigitsIsAccepted(String prefix) {
        assertThat(invalidFields(request("R1", "01712345678", settings(0L, 0L, 0, prefix)))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "S", "ABCDEFG", "SD-", "S D", "ঢাকা", "SD_1", " SD"})
    void otherPrefixesGetExactlyOneClearError(String prefix) {
        Set<ConstraintViolation<UpdateShopRequest>> violations =
                validator.validate(request("R1", "01712345678", settings(0L, 0L, 0, prefix)));
        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.getPropertyPath()).hasToString("settings.orderNumberPrefix");
            assertThat(v.getMessage()).isEqualTo(ShopRules.ORDER_NUMBER_PREFIX_MESSAGE);
        });
    }
}

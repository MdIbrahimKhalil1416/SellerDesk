package com.sellerdesk.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerdesk.common.dto.AddressDto;
import com.sellerdesk.common.error.ResourceNotFoundException;
import com.sellerdesk.common.model.Address;
import com.sellerdesk.common.model.DeliveryZone;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.shop.dto.ShopResponse;
import com.sellerdesk.shop.dto.ShopSummary;
import com.sellerdesk.shop.dto.UpdateShopRequest;
import com.sellerdesk.shop.model.Shop;
import com.sellerdesk.shop.model.ShopSettings;
import com.sellerdesk.shop.model.ShopStatus;
import com.sellerdesk.shop.repository.ShopQueryRepository;
import com.sellerdesk.shop.repository.ShopRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;

class ShopServiceTest {

    private final ShopRepository shopRepository = mock(ShopRepository.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final ShopQueryRepository shopQueryRepository = mock(ShopQueryRepository.class);
    private final ShopSettingsLookup settingsLookup = mock(ShopSettingsLookup.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-28T10:00:00Z"), ZoneOffset.UTC);
    private final ShopService service =
            new ShopService(shopRepository, shopQueryRepository, settingsLookup, currentUser, clock);

    @Test
    void createForOwnerSetsIdsStatusAndDefaultSettings() {
        when(shopRepository.save(any(Shop.class))).thenAnswer(inv -> inv.getArgument(0));

        Shop shop = service.createForOwner("shopA", "u1",
                new ShopService.NewShop("Rahim Fashion", "01712345678", null, null));

        assertThat(shop.getId()).isEqualTo("shopA");
        assertThat(shop.getOwnerUserId()).isEqualTo("u1");
        assertThat(shop.getStatus()).isEqualTo(ShopStatus.ACTIVE);
        assertThat(shop.getSettings().deliveryChargeInsideDhaka()).isEqualTo(6_000);
        assertThat(shop.getSettings().deliveryChargeOutsideDhaka()).isEqualTo(12_000);
        assertThat(shop.getSettings().defaultLowStockThreshold()).isEqualTo(5);
        assertThat(shop.getSettings().orderNumberPrefix()).isEqualTo("SD");
        assertThat(shop.getOrderSeq()).isZero();
    }

    @Test
    void getMyShopLoadsTheShopFromTheTokenNotFromInput() {
        when(currentUser.shopId()).thenReturn("shopA");
        Shop shop = new Shop();
        shop.setId("shopA");
        shop.setName("Rahim Fashion");
        shop.setStatus(ShopStatus.ACTIVE);
        when(shopRepository.findById("shopA")).thenReturn(Optional.of(shop));

        ShopResponse response = service.getMyShop();

        verify(shopRepository).findById("shopA");
        assertThat(response.id()).isEqualTo("shopA");
        assertThat(response.name()).isEqualTo("Rahim Fashion");
        assertThat(response.settings().deliveryChargeInsideDhaka()).isEqualTo(6_000);
    }

    @Test
    void findSummaryMapsIdNameStatus() {
        Shop shop = new Shop();
        shop.setId("shopA");
        shop.setName("Rahim Fashion");
        shop.setStatus(ShopStatus.ACTIVE);
        when(shopRepository.findById("shopA")).thenReturn(Optional.of(shop));

        assertThat(service.findSummary("shopA"))
                .contains(new ShopSummary("shopA", "Rahim Fashion", ShopStatus.ACTIVE));
        assertThat(service.findSummary("other")).isEmpty();
    }

    @Test
    void missingShopIs404() {
        when(currentUser.shopId()).thenReturn("shopA");
        when(shopRepository.findById("shopA")).thenReturn(Optional.empty());

        assertThatThrownBy(service::getMyShop)
                .isInstanceOfSatisfying(ResourceNotFoundException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SHOP_NOT_FOUND"));
    }

    @Test
    void userWithoutShopIs403() {
        when(currentUser.shopId()).thenThrow(new AccessDeniedException("No shop context"));

        assertThatThrownBy(service::getMyShop).isInstanceOf(AccessDeniedException.class);
    }

    @Nested
    class UpdateProfileAndSettings {

        private UpdateShopRequest request(String phone, String email, String prefix) {
            return new UpdateShopRequest("  Rahim Fashion BD ", phone, email,
                    new AddressDto(" House 1 ", " ", "Dhaka", DeliveryZone.INSIDE_DHAKA, null),
                    " https://facebook.com/rahim ",
                    new UpdateShopRequest.Settings(7_000L, 15_000L, 3, prefix));
        }

        private Document setOf(org.springframework.data.mongodb.core.query.Update update) {
            return update.getUpdateObject().get("$set", Document.class);
        }

        @BeforeEach
        void shopA() {
            when(currentUser.shopId()).thenReturn("shopA");
            when(shopQueryRepository.updateAndGet(eq("shopA"), any(), any())).thenAnswer(inv -> {
                Shop shop = new Shop();
                shop.setId("shopA");
                shop.setStatus(ShopStatus.ACTIVE);
                return Optional.of(shop);
            });
        }

        @Test
        void normalizesThenWritesOnlyTheFormFieldsOfTheShopFromTheToken() {
            service.update(request("+880 1712-345678", " Hello@Rahim.COM ", "rf24"));

            ArgumentCaptor<org.springframework.data.mongodb.core.query.Update> captor =
                    ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Update.class);
            verify(shopQueryRepository).updateAndGet(eq("shopA"), captor.capture(), eq(clock.instant()));
            Document set = setOf(captor.getValue());

            assertThat(set.get("name")).isEqualTo("Rahim Fashion BD");
            assertThat(set.get("phone")).isEqualTo("01712345678");
            assertThat(set.get("email")).isEqualTo("hello@rahim.com");
            assertThat(set.get("facebookPageUrl")).isEqualTo("https://facebook.com/rahim");
            assertThat(set.get("address")).isEqualTo(new Address("House 1", null, "Dhaka", DeliveryZone.INSIDE_DHAKA, null));
            assertThat(set.get("settings")).isEqualTo(new ShopSettings(7_000, 15_000, 3, "RF24"));
            // Exactly the form fields: never orderSeq, status, ownerUserId or logo, so a settings
            // save cannot reset the order counter or remove the logo.
            assertThat(set.keySet()).containsExactlyInAnyOrder(
                    "name", "phone", "email", "facebookPageUrl", "address", "settings");
            assertThat(captor.getValue().getUpdateObject().keySet()).containsExactly("$set");
        }

        @Test
        void emptyOptionalFieldsAreRemovedNotStoredAsEmptyText() {
            service.update(new UpdateShopRequest("Rahim", "01712345678", "  ", null, "",
                    new UpdateShopRequest.Settings(0L, 0L, 0, "SD")));

            ArgumentCaptor<org.springframework.data.mongodb.core.query.Update> captor =
                    ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Update.class);
            verify(shopQueryRepository).updateAndGet(eq("shopA"), captor.capture(), any());
            Document unset = captor.getValue().getUpdateObject().get("$unset", Document.class);
            assertThat(unset.keySet()).containsExactlyInAnyOrder("email", "address", "facebookPageUrl");
            assertThat(setOf(captor.getValue()).get("settings")).isEqualTo(new ShopSettings(0, 0, 0, "SD"));
        }

        @Test
        void cachedSettingsAreEvictedAfterTheWrite() {
            service.update(request("01712345678", null, "SD"));

            InOrder order = inOrder(shopQueryRepository, settingsLookup);
            order.verify(shopQueryRepository).updateAndGet(eq("shopA"), any(), any());
            order.verify(settingsLookup).evict("shopA");
        }

        @Test
        void missingShopIs404AndNothingIsEvicted() {
            when(shopQueryRepository.updateAndGet(eq("shopA"), any(), any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(request("01712345678", null, "SD")))
                    .isInstanceOfSatisfying(ResourceNotFoundException.class,
                            e -> assertThat(e.getCode()).isEqualTo("SHOP_NOT_FOUND"));
            verify(settingsLookup, never()).evict(any());
        }
    }
}

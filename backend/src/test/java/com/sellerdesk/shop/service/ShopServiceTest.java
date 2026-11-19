package com.sellerdesk.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerdesk.common.error.ResourceNotFoundException;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.shop.dto.ShopResponse;
import com.sellerdesk.shop.dto.ShopSummary;
import com.sellerdesk.shop.model.Shop;
import com.sellerdesk.shop.model.ShopStatus;
import com.sellerdesk.shop.repository.ShopRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class ShopServiceTest {

    private final ShopRepository shopRepository = mock(ShopRepository.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final ShopService service = new ShopService(shopRepository, currentUser);

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
}

package com.sellerdesk.shop.service;

import com.sellerdesk.common.error.ResourceNotFoundException;
import com.sellerdesk.common.model.Address;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.shop.dto.ShopResponse;
import com.sellerdesk.shop.dto.ShopSummary;
import com.sellerdesk.shop.model.Shop;
import com.sellerdesk.shop.model.ShopSettings;
import com.sellerdesk.shop.model.ShopStatus;
import com.sellerdesk.shop.repository.ShopRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ShopService {

    private final ShopRepository shopRepository;
    private final CurrentUser currentUser;

    /** Values for a new shop, already validated and normalized by the caller. */
    public record NewShop(String name, String phone, Address address, String facebookPageUrl) {
    }

    /**
     * Creates a shop during registration. Only {@code AuthService} calls this, inside the same
     * transaction that creates the owner. Both ids are generated in advance by the caller because
     * the two documents point at each other (shop.ownerUserId ↔ user.shopId).
     */
    public Shop createForOwner(String shopId, String ownerUserId, NewShop data) {
        Shop shop = new Shop();
        shop.setId(shopId);
        shop.setOwnerUserId(ownerUserId);
        shop.setName(data.name());
        shop.setPhone(data.phone());
        shop.setAddress(data.address());
        shop.setFacebookPageUrl(data.facebookPageUrl());
        shop.setStatus(ShopStatus.ACTIVE);
        shop.setSettings(ShopSettings.defaults());
        shop.setOrderSeq(0);
        return shopRepository.save(shop);
    }

    /**
     * Short info about a shop, for {@code /auth/me}. {@code shopId} must come from the
     * authenticated user, never from the request.
     */
    public Optional<ShopSummary> findSummary(String shopId) {
        return shopRepository.findById(shopId).map(ShopSummary::from);
    }

    /** The shop of the logged-in user. The id comes from the JWT, never from the request. */
    public ShopResponse getMyShop() {
        String shopId = currentUser.shopId();
        return shopRepository.findById(shopId)
                .map(ShopResponse::from)
                .orElseThrow(() -> ResourceNotFoundException.of("Shop"));
    }
}

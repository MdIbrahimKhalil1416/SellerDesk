package com.sellerdesk.shop.service;

import com.sellerdesk.common.error.ResourceNotFoundException;
import com.sellerdesk.common.model.Address;
import com.sellerdesk.common.phone.BdPhone;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.common.util.Normalize;
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
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ShopService {

    private final ShopRepository shopRepository;
    private final ShopQueryRepository shopQueryRepository;
    private final ShopSettingsLookup settingsLookup;
    private final CurrentUser currentUser;
    private final Clock clock;

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
                .orElseThrow(ShopService::shopNotFound);
    }

    /**
     * Replaces profile and settings of the logged-in user's shop ({@code PUT /shop}). The request
     * is already validated ({@code @Valid}); here it is normalized: phone to {@code 01XXXXXXXXX},
     * email to lowercase, prefix to uppercase, blank optional fields to "not set".
     *
     * <p>Only the fields of the form are written (one {@code $set}), never {@code orderSeq},
     * {@code status}, {@code ownerUserId} or the logo. Then the cached settings are evicted.
     */
    public ShopResponse update(UpdateShopRequest request) {
        String shopId = currentUser.shopId();
        UpdateShopRequest.Settings s = request.settings();
        ShopSettings settings = new ShopSettings(
                s.deliveryChargeInsideDhaka(),
                s.deliveryChargeOutsideDhaka(),
                s.defaultLowStockThreshold(),
                s.orderNumberPrefix().toUpperCase(Locale.ROOT));

        Update update = new Update()
                .set("name", request.name().trim())
                .set("phone", BdPhone.normalize(request.phone()))
                .set("settings", settings);
        setOrUnset(update, "email", Normalize.trimToNull(Normalize.email(request.email())));
        setOrUnset(update, "address", request.address() == null ? null : request.address().toModel());
        setOrUnset(update, "facebookPageUrl", Normalize.trimToNull(request.facebookPageUrl()));

        Shop updated = shopQueryRepository.updateAndGet(shopId, update, Instant.now(clock))
                .orElseThrow(ShopService::shopNotFound);
        settingsLookup.evict(shopId);
        return ShopResponse.from(updated);
    }

    /** An order number: {@code number} from the shop's sequence, shown as {@code prefix-number}. */
    public record OrderNumber(String prefix, long number) {
    }

    /**
     * The next order number of the logged-in user's shop (docs/modules/ORDER.md §3): an atomic
     * {@code $inc} of {@code orderSeq}, plus the current prefix, in one round trip.
     *
     * <p>Must be called <b>outside</b> any transaction: inside one, every parallel order create
     * would write the same shop document and all but one would fail with a write conflict. The
     * price: an order create that fails after this call leaves a gap in the numbers.
     */
    public OrderNumber nextOrderNumber() {
        String shopId = currentUser.shopId();
        Shop shop = shopQueryRepository.incrementOrderSeq(shopId).orElseThrow(ShopService::shopNotFound);
        ShopSettings settings = shop.getSettings() == null ? ShopSettings.defaults() : shop.getSettings();
        return new OrderNumber(settings.orderNumberPrefix(), shop.getOrderSeq());
    }

    static ResourceNotFoundException shopNotFound() {
        return ResourceNotFoundException.of("Shop");
    }

    /** An empty optional field is removed from the document instead of stored as {@code null}. */
    private static void setOrUnset(Update update, String field, Object value) {
        if (value == null) {
            update.unset(field);
        } else {
            update.set(field, value);
        }
    }
}

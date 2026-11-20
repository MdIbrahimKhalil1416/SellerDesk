package com.sellerdesk.product.service;

import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.common.security.Permission;
import com.sellerdesk.product.dto.ProductLookupItem;
import com.sellerdesk.product.dto.ProductResponse;
import com.sellerdesk.product.dto.ProductSummary;
import com.sellerdesk.product.model.Product;
import com.sellerdesk.product.model.ProductImage;
import com.sellerdesk.product.model.Variant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Turns {@link Product} documents into response DTOs. Controllers never return the document.
 *
 * <p>Decides per request what the user may see (docs/SECURITY.md §3):
 * {@code costPrice} only with PRODUCT_EDIT or REPORT_VIEW, {@code stock} only with STOCK_VIEW.
 * Hidden fields are {@code null} and therefore left out of the JSON.
 */
@Component
@RequiredArgsConstructor
public class ProductMapper {

    private final CurrentUser currentUser;

    public ProductResponse toResponse(Product product) {
        boolean showCost = currentUser.hasPermission(Permission.PRODUCT_EDIT)
                || currentUser.hasPermission(Permission.REPORT_VIEW);
        boolean showStock = currentUser.hasPermission(Permission.STOCK_VIEW);

        List<ProductResponse.ImageResponse> images = product.getImages().stream()
                .map(i -> new ProductResponse.ImageResponse(i.getImageId(), i.getUrl(), i.getWidth(), i.getHeight()))
                .toList();
        List<ProductResponse.VariantResponse> variants = product.getVariants().stream()
                .map(v -> new ProductResponse.VariantResponse(
                        v.getVariantId(), v.getSku(), v.getName(), attributes(v), v.getPrice(),
                        showCost ? v.getCostPrice() : null,
                        v.getLowStockThreshold(), v.isActive(),
                        showStock && v.getStock() != null ? new ProductResponse.StockResponse(
                                v.getStock().onHand(), v.getStock().reserved(), v.getStock().available()) : null))
                .toList();
        return new ProductResponse(product.getId(), product.getName(), product.getDescription(),
                product.getCategory(), product.getStatus(), images, variants, product.getVersion(),
                product.getCreatedAt(), product.getUpdatedAt());
    }

    public ProductSummary toSummary(Product product) {
        Integer totalAvailable = null;
        if (currentUser.hasPermission(Permission.STOCK_VIEW)) {
            totalAvailable = product.getVariants().stream()
                    .mapToInt(v -> v.getStock() == null ? 0 : v.getStock().available())
                    .sum();
        }
        return new ProductSummary(product.getId(), product.getName(), product.getCategory(), product.getStatus(),
                product.getVariants().size(),
                new ProductSummary.PriceRange(product.getMinPrice(), product.getMaxPrice()),
                totalAvailable, coverUrl(product), product.getUpdatedAt());
    }

    /**
     * Lookup entry: active variants only; empty if none is active. Holds nothing that depends on
     * the user, because the result is cached and shared by the whole shop.
     */
    public Optional<ProductLookupItem> toLookupItem(Product product) {
        List<ProductLookupItem.LookupVariant> variants = product.getVariants().stream()
                .filter(Variant::isActive)
                .map(v -> new ProductLookupItem.LookupVariant(v.getVariantId(), v.getSku(), v.getName(), v.getPrice()))
                .toList();
        if (variants.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ProductLookupItem(product.getId(), product.getName(), coverUrl(product), variants));
    }

    /** Read-only copy that keeps the order the seller typed (Color before Size). */
    private static Map<String, String> attributes(Variant variant) {
        return variant.getAttributes() == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(variant.getAttributes()));
    }

    private static String coverUrl(Product product) {
        List<ProductImage> images = product.getImages();
        return images == null || images.isEmpty() ? null : images.getFirst().getUrl();
    }
}

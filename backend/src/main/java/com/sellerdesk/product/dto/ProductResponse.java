package com.sellerdesk.product.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sellerdesk.product.model.ProductStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A product with all its variants and images. Built by {@code ProductMapper}, which leaves out
 * {@code costPrice} without PRODUCT_EDIT / REPORT_VIEW and {@code stock} without STOCK_VIEW
 * (the fields are then missing from the JSON, not {@code null}).
 */
public record ProductResponse(
        String id,
        String name,
        String description,
        String category,
        ProductStatus status,
        List<ImageResponse> images,
        List<VariantResponse> variants,
        Long version,
        Instant createdAt,
        Instant updatedAt) {

    public record ImageResponse(String id, String url, int width, int height) {
    }

    /** Money in poisha. {@code lowStockThreshold == null} = shop default. */
    public record VariantResponse(
            String variantId,
            String sku,
            String name,
            Map<String, String> attributes,
            long price,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long costPrice,
            Integer lowStockThreshold,
            boolean active,
            @JsonInclude(JsonInclude.Include.NON_NULL) StockResponse stock) {
    }

    public record StockResponse(int onHand, int reserved, int available) {
    }
}

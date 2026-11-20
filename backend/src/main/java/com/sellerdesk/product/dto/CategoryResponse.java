package com.sellerdesk.product.dto;

/** A category in use, for autocomplete. {@code productCount} counts active and archived products. */
public record CategoryResponse(String name, long productCount) {
}

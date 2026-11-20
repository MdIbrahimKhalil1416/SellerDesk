package com.sellerdesk.product.repository;

import java.util.Arrays;
import java.util.Optional;
import org.springframework.data.domain.Sort;

/** The sort orders {@code GET /products?sort=} accepts. Anything else is a 400. */
public enum ProductSort {

    UPDATED_DESC("updatedAt,desc", Sort.by(Sort.Direction.DESC, "updatedAt")),
    NAME_ASC("name,asc", Sort.by(Sort.Direction.ASC, "nameLower")),
    NAME_DESC("name,desc", Sort.by(Sort.Direction.DESC, "nameLower")),
    CREATED_DESC("createdAt,desc", Sort.by(Sort.Direction.DESC, "createdAt")),
    MIN_PRICE_ASC("minPrice,asc", Sort.by(Sort.Direction.ASC, "minPrice")),
    MIN_PRICE_DESC("minPrice,desc", Sort.by(Sort.Direction.DESC, "minPrice"));

    /** For {@code @Pattern} on the request parameter. Keep in sync with the constants above. */
    public static final String PARAM_REGEX =
            "updatedAt,desc|name,asc|name,desc|createdAt,desc|minPrice,asc|minPrice,desc";

    private final String param;
    private final Sort sort;

    ProductSort(String param, Sort sort) {
        this.param = param;
        // _id as tie-breaker: pages stay stable when many products share a value.
        this.sort = sort.and(Sort.by(Sort.Direction.ASC, "_id"));
    }

    public static Optional<ProductSort> fromParam(String param) {
        return Arrays.stream(values()).filter(s -> s.param.equals(param)).findFirst();
    }

    public String param() {
        return param;
    }

    public Sort sort() {
        return sort;
    }
}

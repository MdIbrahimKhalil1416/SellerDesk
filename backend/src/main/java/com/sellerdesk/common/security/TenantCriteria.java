package com.sellerdesk.common.security;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.query.Criteria;

/**
 * Start of every hand-written {@code MongoTemplate} query on tenant data (docs/SECURITY.md §4,
 * layer 4):
 *
 * <pre>{@code
 * Query query = new Query(TenantCriteria.of(shopId).and("status").is("ACTIVE"));
 * }</pre>
 *
 * <p>Starting from this helper (instead of {@code Criteria.where(...)}) makes a missing shop
 * filter easy to spot in code review: every tenant query begins with the same line.
 *
 * <p>{@code shopId} is stored as an ObjectId (docs/SCHEMA.md §1), so the value is converted
 * here. That also works in aggregations, where Spring does not convert types for us.
 */
public final class TenantCriteria {

    public static final String SHOP_ID = "shopId";

    private TenantCriteria() {
    }

    /** @param shopId the current tenant, from {@link TenantContext} / {@link CurrentUser}, never from the request */
    public static Criteria of(String shopId) {
        if (shopId == null || !ObjectId.isValid(shopId)) {
            // A programming error (e.g. a job forgot to pass the shop), never user input.
            throw new IllegalArgumentException("Tenant query without a valid shopId");
        }
        return Criteria.where(SHOP_ID).is(new ObjectId(shopId));
    }
}

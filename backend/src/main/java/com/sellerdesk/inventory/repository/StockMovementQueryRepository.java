package com.sellerdesk.inventory.repository;

import com.sellerdesk.common.security.TenantCriteria;
import com.sellerdesk.inventory.model.AdjustmentReason;
import com.sellerdesk.inventory.model.MovementType;
import com.sellerdesk.inventory.model.StockMovement;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/**
 * The movement history with optional filters, newest first. Starts with
 * {@link TenantCriteria#of} (docs/SECURITY.md §4, layer 4) and uses the indexes that begin with
 * {@code shopId}, then {@code variantId} / {@code productId} / {@code orderId}, then {@code createdAt}.
 */
@Repository
@RequiredArgsConstructor
public class StockMovementQueryRepository {

    /** Filters; {@code null} = no filter. Ids must already be valid ObjectIds. */
    public record Filter(String variantId, String productId, MovementType type, AdjustmentReason reasonCode,
                         String orderId, Instant from, Instant to) {
    }

    public record Result(List<StockMovement> content, long totalElements) {
    }

    private final MongoTemplate mongoTemplate;

    public Result search(String shopId, Filter filter, int page, int size) {
        Criteria criteria = TenantCriteria.of(shopId);
        if (filter.variantId() != null) {
            criteria = criteria.and("variantId").is(new ObjectId(filter.variantId()));
        }
        if (filter.productId() != null) {
            criteria = criteria.and("productId").is(new ObjectId(filter.productId()));
        }
        if (filter.orderId() != null) {
            criteria = criteria.and("orderId").is(new ObjectId(filter.orderId()));
        }
        if (filter.type() != null) {
            criteria = criteria.and("type").is(filter.type());
        }
        if (filter.reasonCode() != null) {
            criteria = criteria.and("reasonCode").is(filter.reasonCode());
        }
        if (filter.from() != null || filter.to() != null) {
            Criteria createdAt = criteria.and("createdAt");
            if (filter.from() != null) {
                createdAt.gte(filter.from());
            }
            if (filter.to() != null) {
                createdAt.lt(filter.to());
            }
        }
        Query query = new Query(criteria);
        long total = mongoTemplate.count(query, StockMovement.class);
        // _id as tie-breaker: movements created in the same millisecond keep insertion order.
        query.with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")))
                .skip((long) page * size)
                .limit(size);
        return new Result(mongoTemplate.find(query, StockMovement.class), total);
    }

    /** The newest {@code limit} movements of one variant (variant detail page). */
    public List<StockMovement> latestForVariant(String shopId, String variantId, int limit) {
        Query query = new Query(TenantCriteria.of(shopId).and("variantId").is(new ObjectId(variantId)))
                .with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")))
                .limit(limit);
        return mongoTemplate.find(query, StockMovement.class);
    }
}

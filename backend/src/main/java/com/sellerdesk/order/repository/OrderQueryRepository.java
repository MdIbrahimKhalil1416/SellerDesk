package com.sellerdesk.order.repository;

import com.sellerdesk.common.security.TenantCriteria;
import com.sellerdesk.order.model.Order;
import com.sellerdesk.order.model.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * Targeted writes to one order.
 *
 * <p>Why not {@code save(order)}: a save writes the whole document it loaded. Two people working on
 * the same order (one confirms, one edits the note) would overwrite each other. Instead, every
 * write says <b>what it expects</b> (the status, and for edits also the version) in its filter. If
 * someone else changed the order in between, the filter matches nothing and the caller decides.
 */
@Repository
@RequiredArgsConstructor
public class OrderQueryRepository {

    private final MongoTemplate mongoTemplate;

    /**
     * Applies {@code update} (plus {@code version + 1}) only if the order is still in
     * {@code expectedStatus} and, when given, still has {@code expectedVersion}.
     *
     * @return true if the order matched and was changed
     */
    public boolean updateIf(String shopId, String orderId, OrderStatus expectedStatus, Long expectedVersion,
                            Update update) {
        Criteria criteria = TenantCriteria.of(shopId).and("_id").is(new ObjectId(orderId)).and("status").is(expectedStatus);
        if (expectedVersion != null) {
            criteria = criteria.and("version").is(expectedVersion);
        }
        update.inc("version", 1);
        return mongoTemplate.updateFirst(new Query(criteria), update, Order.class).getMatchedCount() == 1;
    }
}

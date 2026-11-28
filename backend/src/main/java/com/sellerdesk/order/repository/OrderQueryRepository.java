package com.sellerdesk.order.repository;

import com.sellerdesk.common.security.TenantCriteria;
import com.sellerdesk.order.model.Order;
import com.sellerdesk.order.model.OrderSource;
import com.sellerdesk.order.model.OrderStatus;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * Targeted writes to one order, and the order list.
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

    // ==== List ===========================================================================

    /**
     * Filters of the order list; {@code null} / empty means "no filter".
     *
     * @param statuses    any of these statuses
     * @param from        {@code createdAt >= from}
     * @param toExclusive {@code createdAt < toExclusive}
     * @param orderNumber exact number (unique per shop)
     * @param phone       exact {@code customer.phone}, normalized {@code 01XXXXXXXXX}
     */
    public record ListFilter(Set<OrderStatus> statuses, OrderSource source, Instant from, Instant toExclusive,
                             Long orderNumber, String phone, boolean ascending, int page, int size) {
    }

    public record ListResult(List<Order> content, long totalElements) {
    }

    /**
     * One page of the shop's orders, sorted by {@code createdAt} then {@code _id}. Only the fields
     * of the list row are loaded.
     */
    public ListResult list(String shopId, ListFilter filter) {
        Query query = listQuery(shopId, filter);
        long total = mongoTemplate.count(Query.of(query).skip(0).limit(0), Order.class);
        query.fields().include("orderNumber", "orderNumberPrefix", "status", "source", "customerId", "customer",
                "items.quantity", "total", "codAmount", "createdAt");
        return new ListResult(mongoTemplate.find(query, Order.class), total);
    }

    /**
     * The list query: filter, sort and page. Public so a test can ask MongoDB how it runs it
     * ({@code explain}) and prove it uses an index starting with {@code shopId}.
     *
     * <p>Every value is already in its stored BSON type (ObjectId, String, Date), so the query can
     * also be sent to the raw collection unchanged.
     */
    public Query listQuery(String shopId, ListFilter f) {
        Criteria criteria = TenantCriteria.of(shopId);
        if (f.orderNumber() != null) {
            criteria = criteria.and("orderNumber").is(f.orderNumber());
        }
        if (f.phone() != null) {
            criteria = criteria.and("customer.phone").is(f.phone());
        }
        if (f.statuses() != null && !f.statuses().isEmpty()) {
            criteria = criteria.and("status").in(f.statuses().stream().map(Enum::name).sorted().toList());
        }
        if (f.source() != null) {
            criteria = criteria.and("source").is(f.source().name());
        }
        if (f.from() != null || f.toExclusive() != null) {
            Criteria created = criteria.and("createdAt");
            if (f.from() != null) {
                created = created.gte(Date.from(f.from()));
            }
            if (f.toExclusive() != null) {
                created = created.lt(Date.from(f.toExclusive()));
            }
            criteria = created;
        }
        Sort.Direction direction = f.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC;
        return new Query(criteria)
                .with(Sort.by(direction, "createdAt").and(Sort.by(direction, "_id")))
                .skip((long) f.page() * f.size())
                .limit(f.size());
    }
}

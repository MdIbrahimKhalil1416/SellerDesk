package com.sellerdesk.courier.repository;

import com.sellerdesk.common.security.TenantCriteria;
import com.sellerdesk.courier.model.CourierCode;
import com.sellerdesk.courier.model.Shipment;
import com.sellerdesk.courier.model.ShipmentEvent;
import com.sellerdesk.courier.model.ShipmentStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * Conditional writes to one shipment, and the shipment list. Like orders: every write says what
 * it expects (the status, and for courier events that the event is new), so parallel requests
 * (two webhook copies, a cancel during a booking) can never overwrite each other.
 */
@Repository
@RequiredArgsConstructor
public class ShipmentQueryRepository {

    private final MongoTemplate mongoTemplate;

    /**
     * Applies {@code update} (plus {@code version + 1}) only if the shipment is still in
     * {@code expectedStatus} and, when given, has no event with {@code newEventKey} yet.
     *
     * @return true if it matched and was changed
     */
    public boolean updateIf(String shopId, String shipmentId, ShipmentStatus expectedStatus, String newEventKey,
                            Update update) {
        Criteria criteria = TenantCriteria.of(shopId).and("_id").is(new ObjectId(shipmentId));
        if (expectedStatus != null) {
            criteria = criteria.and("status").is(expectedStatus);
        }
        if (newEventKey != null) {
            criteria = criteria.and("events.eventKey").ne(newEventKey);
        }
        update.inc("version", 1);
        return mongoTemplate.updateFirst(new Query(criteria), update, Shipment.class).getMatchedCount() == 1;
    }

    /** Appends an event to an update, keeping the newest {@link Shipment#MAX_EVENTS}. */
    public static Update pushEvent(Update update, ShipmentEvent event) {
        update.push("events").slice(-Shipment.MAX_EVENTS).each(event);
        return update;
    }

    public record ListResult(List<Shipment> content, long totalElements) {
    }

    /** One page of the shop's shipments, newest change first (index {@code shopId_status_updatedAt}). */
    public ListResult list(String shopId, ShipmentStatus status, CourierCode provider, int page, int size) {
        Criteria criteria = TenantCriteria.of(shopId);
        if (status != null) {
            criteria = criteria.and("status").is(status);
        }
        if (provider != null) {
            criteria = criteria.and("provider").is(provider);
        }
        Query query = new Query(criteria);
        long total = mongoTemplate.count(query, Shipment.class);
        query.with(Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("_id")))
                .skip((long) page * size).limit(size);
        query.fields().exclude("events", "recipient");
        return new ListResult(mongoTemplate.find(query, Shipment.class), total);
    }
}

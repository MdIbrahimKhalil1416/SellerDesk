package com.sellerdesk.courier.repository;

import com.sellerdesk.courier.model.CourierCode;
import com.sellerdesk.courier.model.Shipment;
import com.sellerdesk.courier.model.ShipmentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/**
 * <b>Exception to the tenant rule</b> (docs/SECURITY.md §4, docs/modules/COURIER.md §3): the only
 * shipment reads without a {@code shopId}.
 *
 * <ul>
 *   <li>A courier webhook arrives without any tenant: only the provider and the consignment id (or
 *       our merchant reference) are known. This class finds out <b>which shop</b> it belongs to.</li>
 *   <li>The booking reconciler and the tracking sweep are background jobs for all shops.</li>
 * </ul>
 * It returns only {@link ShipmentRoute} (ids), never shipment data. Everything after routing runs
 * inside the found shop ({@code SystemActorScope}) with the normal shopId repositories.
 */
@Repository
@RequiredArgsConstructor
public class ShipmentRoutingRepository {

    /** The shipment and its shop. Nothing else leaves this class. */
    public record ShipmentRoute(String shipmentId, String shopId) {
    }

    private final MongoTemplate mongoTemplate;

    /** Webhook: by the courier's consignment id (index {@code provider_consignmentId}). */
    public Optional<ShipmentRoute> byConsignment(CourierCode provider, String consignmentId) {
        if (consignmentId == null) {
            return Optional.empty();
        }
        return one(Criteria.where("provider").is(provider.name()).and("consignmentId").is(consignmentId));
    }

    /** Webhook that beat our CREATED write: by our merchant reference (= the shipment id). */
    public Optional<ShipmentRoute> byMerchantRef(CourierCode provider, String merchantRef) {
        if (merchantRef == null || !ObjectId.isValid(merchantRef)) {
            return Optional.empty();
        }
        return one(Criteria.where("_id").is(new ObjectId(merchantRef)).and("provider").is(provider.name()));
    }

    /**
     * Booking reconciler: BOOKING shipments that are due ({@code nextAttemptAt ≤ now}), or that were
     * left without a {@code nextAttemptAt} by a crash and are older than {@code staleBefore}.
     */
    public List<ShipmentRoute> dueBookings(Instant now, Instant staleBefore, int limit) {
        Criteria due = new Criteria().orOperator(
                Criteria.where("nextAttemptAt").lte(now),
                new Criteria().andOperator(Criteria.where("nextAttemptAt").exists(false),
                        Criteria.where("createdAt").lt(staleBefore)));
        return many(new Criteria().andOperator(Criteria.where("status").is(ShipmentStatus.BOOKING.name()), due),
                Sort.by("nextAttemptAt"), limit);
    }

    /** Tracking sweep: booked shipments with no courier news since {@code silentSince}. */
    public List<ShipmentRoute> silentActive(Instant silentSince, int limit) {
        return many(Criteria.where("active").is(true).and("lastEventAt").lt(silentSince)
                .and("status").in(ShipmentStatus.CREATED.name(), ShipmentStatus.PICKED_UP.name(), ShipmentStatus.IN_TRANSIT.name()),
                Sort.by("lastEventAt"), limit);
    }

    /** Active shipments of one provider, for the mock to rebuild its parcels after a restart. */
    public List<Shipment> activeWithConsignment(CourierCode provider) {
        Query query = new Query(Criteria.where("active").is(true).and("provider").is(provider)
                .and("consignmentId").exists(true));
        query.fields().include("merchantRef", "consignmentId", "trackingCode", "codAmount", "status");
        return mongoTemplate.find(query, Shipment.class);
    }

    private Optional<ShipmentRoute> one(Criteria criteria) {
        Query query = new Query(criteria);
        query.fields().include("_id", "shopId");
        return Optional.ofNullable(mongoTemplate.findOne(query, Document.class, "shipments")).map(ShipmentRoutingRepository::route);
    }

    private List<ShipmentRoute> many(Criteria criteria, Sort sort, int limit) {
        Query query = new Query(criteria).with(sort).limit(limit);
        query.fields().include("_id", "shopId");
        return mongoTemplate.find(query, Document.class, "shipments").stream().map(ShipmentRoutingRepository::route).toList();
    }

    private static ShipmentRoute route(Document d) {
        return new ShipmentRoute(d.getObjectId("_id").toHexString(), d.getObjectId("shopId").toHexString());
    }
}

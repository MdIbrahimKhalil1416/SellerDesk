package com.sellerdesk.courier.model;

import com.sellerdesk.common.model.AuditableDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * One courier booking of an order (docs/modules/COURIER.md §3). A failed or cancelled booking
 * stays as history; sending again creates a new document.
 *
 * <p>Inserted once; every later change is a conditional update in {@code ShipmentQueryRepository}
 * that states the status it expects, like orders.
 *
 * <p>Indexes 1–3 start with {@code shopId}. Indexes 4–6 are the <b>documented exception</b>
 * (docs/SCHEMA.md §10): a webhook arrives without a tenant (only provider + consignment id are
 * known), and the reconciler / tracking sweep are jobs for all shops. Only
 * {@code ShipmentRoutingRepository} uses them.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
@Document("shipments")
@CompoundIndexes({
        @CompoundIndex(name = "shopId_orderId_createdAt", def = "{'shopId': 1, 'orderId': 1, 'createdAt': -1}"),
        // At most one active shipment per order: two "Send" clicks at once cannot book two parcels.
        @CompoundIndex(name = "shopId_orderId_active", def = "{'shopId': 1, 'orderId': 1}", unique = true,
                partialFilter = "{'active': true}"),
        @CompoundIndex(name = "shopId_status_updatedAt", def = "{'shopId': 1, 'status': 1, 'updatedAt': -1}"),
        // Cross-shop (webhook routing): a consignment id is unique per provider.
        @CompoundIndex(name = "provider_consignmentId", def = "{'provider': 1, 'consignmentId': 1}", unique = true,
                partialFilter = "{'consignmentId': {'$exists': true}}"),
        // Cross-shop (booking reconciler).
        @CompoundIndex(name = "status_nextAttemptAt", def = "{'status': 1, 'nextAttemptAt': 1}",
                partialFilter = "{'status': 'BOOKING'}"),
        // Cross-shop (tracking sweep for lost webhooks).
        @CompoundIndex(name = "active_lastEventAt", def = "{'active': 1, 'lastEventAt': 1}",
                partialFilter = "{'active': true}")
})
public class Shipment extends AuditableDocument {

    public static final int MAX_EVENTS = 100;

    @Id
    private String id;

    @Field(targetType = FieldType.OBJECT_ID)
    private String shopId;

    @Field(targetType = FieldType.OBJECT_ID)
    private String orderId;

    /** Display snapshot, {@code SD-1042}. */
    private String orderNumber;

    private CourierCode provider;

    /** Our reference sent to the courier = {@code _id} as hex: unique, never reused, same on every retry. */
    private String merchantRef;

    private String consignmentId;

    private String trackingCode;

    private ShipmentStatus status;

    /** Mirrors {@code status.active()}; set in the same update, exists for the partial indexes. */
    private boolean active;

    /** Poisha, copy of the order's {@code codAmount} at booking. */
    private long codAmount;

    private Long collectedAmount;

    private Boolean codMismatch;

    private Long courierFee;

    private Recipient recipient;

    private String note;

    /** Units in the parcel (sum of item quantities), sent with every booking call. */
    private int itemCount;

    private int bookingAttempts;

    private Instant nextAttemptAt;

    private String failureReason;

    private Boolean needsManualCheck;

    private String cancelReason;

    /** Last courier event or successful poll; drives the tracking sweep. */
    private Instant lastEventAt;

    /** Last call to the provider's track API (the track endpoint calls it at most once per 60 s). */
    private Instant lastTrackedAt;

    private List<ShipmentEvent> events = new ArrayList<>();

    @Field(targetType = FieldType.OBJECT_ID)
    private String createdBy;

    private String createdByName;

    @Version
    private Long version;
}

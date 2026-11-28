package com.sellerdesk.order.model;

import com.sellerdesk.common.model.AuditableDocument;
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
 * An order of a shop (docs/modules/ORDER.md §2). Items and customer are snapshots; all money is
 * poisha and computed by the server.
 *
 * <p><b>Saved as a whole only once, on create</b> ({@code insert}). Every later change (edit,
 * status change) is a targeted update in {@code OrderQueryRepository} with a condition on the
 * status (and for edits the version), so two people can never overwrite each other.
 *
 * <p>Indexes all start with {@code shopId} (docs/SCHEMA.md §10).
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
@Document("orders")
@CompoundIndexes({
        @CompoundIndex(name = "shopId_orderNumber", def = "{'shopId': 1, 'orderNumber': 1}", unique = true),
        @CompoundIndex(name = "shopId_status_createdAt", def = "{'shopId': 1, 'status': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "shopId_createdAt", def = "{'shopId': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "shopId_customerId_createdAt", def = "{'shopId': 1, 'customerId': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "shopId_customerPhone_createdAt", def = "{'shopId': 1, 'customer.phone': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "shopId_requestId", def = "{'shopId': 1, 'requestId': 1}", unique = true,
                partialFilter = "{'requestId': {'$exists': true}}"),
        @CompoundIndex(name = "shopId_itemsProductId", def = "{'shopId': 1, 'items.productId': 1}")
})
public class Order extends AuditableDocument {

    /** Edits kept for the timeline; older ones are dropped. */
    public static final int MAX_EDITS = 50;

    @Id
    private String id;

    @Field(targetType = FieldType.OBJECT_ID)
    private String shopId;

    /** Per-shop sequence ({@code shops.orderSeq}); unique per shop. */
    private long orderNumber;

    /** Copy of the shop's prefix at creation, so a later prefix change never renames old orders. */
    private String orderNumberPrefix;

    /** Client UUID of the create request: a repeated create returns this order. */
    private String requestId;

    @Field(targetType = FieldType.OBJECT_ID)
    private String customerId;

    private OrderCustomerSnapshot customer;

    private List<OrderItem> items = new ArrayList<>();

    private long subtotal;
    private long discount;
    private long deliveryCharge;
    private long total;
    private long advancePaid;
    private long codAmount;

    private OrderStatus status;

    private List<StatusChange> statusHistory = new ArrayList<>();

    private List<OrderEdit> edits = new ArrayList<>();

    private OrderSource source;

    private String note;

    private Shipping shipping;

    private String cancelReason;

    private String returnReason;

    @Field(targetType = FieldType.OBJECT_ID)
    private String createdBy;

    private String createdByName;

    /** Bumped by every write; edits must send the version they loaded. */
    @Version
    private Long version;

    /** {@code SD-1042}. */
    public String displayNumber() {
        return orderNumberPrefix + "-" + orderNumber;
    }
}

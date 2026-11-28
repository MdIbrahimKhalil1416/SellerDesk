package com.sellerdesk.inventory.model;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * One stock change of one variant (docs/modules/INVENTORY.md §3). <b>Append-only</b>: inserted
 * once, never updated or deleted. A mistake is corrected with a new {@link MovementType#ADJUSTMENT}.
 *
 * <p>Deltas are signed: the sum of all {@code onHandDelta} of a variant is its {@code onHand}, the
 * sum of all {@code reservedDelta} its {@code reserved}. The {@code *After} fields are the
 * variant's balances right after this change, read from the updated product document.
 *
 * <p>Every index starts with {@code shopId} (docs/SCHEMA.md §10). The {@code requestId} index is
 * unique per shop and partial (only movements that have one), so a repeated restock request can
 * never be stored twice.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
@Document("stock_movements")
@CompoundIndexes({
        @CompoundIndex(name = "shopId_variantId_createdAt", def = "{'shopId': 1, 'variantId': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "shopId_createdAt", def = "{'shopId': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "shopId_productId_createdAt", def = "{'shopId': 1, 'productId': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "shopId_orderId", def = "{'shopId': 1, 'orderId': 1}",
                partialFilter = "{'orderId': {'$exists': true}}"),
        @CompoundIndex(name = "shopId_requestId", def = "{'shopId': 1, 'requestId': 1}", unique = true,
                partialFilter = "{'requestId': {'$exists': true}}")
})
public class StockMovement {

    @Id
    private String id;

    @Field(targetType = FieldType.OBJECT_ID)
    private String shopId;

    @Field(targetType = FieldType.OBJECT_ID)
    private String productId;

    @Field(targetType = FieldType.OBJECT_ID)
    private String variantId;

    /** Snapshot. */
    private String sku;

    /** Snapshot at the time of the movement. */
    private String variantName;

    private MovementType type;

    private int onHandDelta;

    private int reservedDelta;

    private int onHandAfter;

    private int reservedAfter;

    private int availableAfter;

    /** Only for {@link MovementType#ADJUSTMENT}. */
    private AdjustmentReason reasonCode;

    /** Optional, ≤ 200 chars; required when {@code reasonCode == OTHER}. */
    private String note;

    /** Order-driven movements only; {@code null} (not stored) otherwise. */
    @Field(targetType = FieldType.OBJECT_ID)
    private String orderId;

    /** Display snapshot, e.g. {@code SD-1042}. */
    private String orderNumber;

    /** Client UUID for restock / adjustment; {@code null} (not stored) for order movements. */
    private String requestId;

    @Field(targetType = FieldType.OBJECT_ID)
    private String createdBy;

    /** Snapshot, so the log needs no join with users. */
    private String createdByName;

    private Instant createdAt;
}

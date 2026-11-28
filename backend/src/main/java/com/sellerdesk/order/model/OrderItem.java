package com.sellerdesk.order.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * One line of an order, as a <b>snapshot</b> (docs/modules/ORDER.md §2): name, SKU and price are
 * copied when the item is added and never re-read from the product. Renaming or repricing the
 * product later does not change orders already taken.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class OrderItem {

    @Field(targetType = FieldType.OBJECT_ID)
    private String productId;

    @Field(targetType = FieldType.OBJECT_ID)
    private String variantId;

    private String sku;

    private String productName;

    private String variantName;

    /** Poisha. */
    private long unitPrice;

    private int quantity;

    /** {@code unitPrice × quantity}, poisha. */
    private long lineTotal;

    /** Set on return: was this item put back on the shelf? {@code null} before a return. */
    private Boolean returnedRestocked;
}

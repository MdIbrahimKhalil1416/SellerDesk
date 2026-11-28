package com.sellerdesk.order.model;

import java.time.Instant;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * One entry of an order's status history. Written in the same update as the status itself, never
 * edited. The first entry ({@code from = null, to = PENDING}) is written at create.
 *
 * @param byName snapshot of the user's name, so the history needs no join
 * @param note   optional note; for cancel and return the reason
 */
public record StatusChange(OrderStatus from, OrderStatus to, Instant at,
                           @Field(targetType = FieldType.OBJECT_ID) String by, String byName, String note) {
}

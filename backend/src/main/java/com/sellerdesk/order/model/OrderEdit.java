package com.sellerdesk.order.model;

import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * One edit of an order, for the timeline: who changed which fields. The order keeps the last
 * {@link Order#MAX_EDITS} edits.
 *
 * @param fields  names of the changed fields, e.g. {@code ["items", "discount"]}
 * @param summary short human text, e.g. {@code "TEE-M qty 1 → 2"}
 */
public record OrderEdit(Instant at, @Field(targetType = FieldType.OBJECT_ID) String by, String byName,
                        List<String> fields, String summary) {
}

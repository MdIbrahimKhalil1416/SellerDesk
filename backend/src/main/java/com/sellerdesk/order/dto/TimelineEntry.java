package com.sellerdesk.order.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sellerdesk.order.model.OrderStatus;
import java.time.Instant;
import java.util.List;

/**
 * One event of an order's timeline: a status change ({@code STATUS}: {@code from}, {@code to},
 * {@code note}) or an edit ({@code EDIT}: {@code fields}, {@code summary}). Fields that do not
 * apply are left out of the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TimelineEntry(Type type, Instant at, OrderResponse.Actor by, OrderStatus from, OrderStatus to,
                            String note, List<String> fields, String summary) {

    public enum Type { STATUS, EDIT }
}

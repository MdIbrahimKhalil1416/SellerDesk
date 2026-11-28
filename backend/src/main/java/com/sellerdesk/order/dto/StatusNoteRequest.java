package com.sellerdesk.order.dto;

import com.sellerdesk.order.service.OrderRules;
import jakarta.validation.constraints.Size;

/** Body of confirm / pack / deliver: an optional note for the status history. The body may be left out. */
public record StatusNoteRequest(@Size(max = OrderRules.MAX_REASON) String note) {
}

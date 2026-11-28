package com.sellerdesk.courier.model;

import java.time.Instant;

/**
 * One entry of a shipment's event log, newest last, capped at {@link Shipment#MAX_EVENTS}.
 *
 * @param at             our time
 * @param status         our status after the event; {@code null} when it changed nothing
 * @param providerStatus the courier's own status text (≤ 50), {@code null} for our events
 * @param providerTime   the courier's time of the event, if it sends one
 * @param eventKey       idempotency key (docs/modules/COURIER.md §5); never returned by the API
 * @param ignored        true for an illegal status jump: stored for debugging, changed nothing
 * @param note           ≤ 300, e.g. why it was ignored, or the courier's location text
 */
public record ShipmentEvent(Instant at, EventSource source, ShipmentStatus status, String providerStatus,
                            Instant providerTime, String eventKey, boolean ignored, String note) {
}

package com.sellerdesk.courier.provider;

import com.sellerdesk.courier.model.ShipmentStatus;
import java.time.Instant;

/**
 * One status report from a courier (webhook or poll), in our types.
 *
 * @param status          mapped status; {@code null} if the courier's status means no change
 *                        (e.g. a location update) or is unknown to the adapter
 * @param providerStatus  the courier's own status text, kept for the event log
 * @param providerEventId the courier's event id, if it has one (best idempotency key)
 * @param collectedAmount poisha, on delivery
 * @param courierFee      poisha, if the courier reports it
 */
public record CourierUpdate(ShipmentStatus status, String providerStatus, String providerEventId,
                            Instant providerTime, Long collectedAmount, Long courierFee, String note) {
}

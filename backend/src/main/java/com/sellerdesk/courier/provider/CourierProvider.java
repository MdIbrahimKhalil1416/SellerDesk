package com.sellerdesk.courier.provider;

import com.sellerdesk.courier.model.CourierCode;
import java.util.Optional;
import org.springframework.http.HttpHeaders;

/**
 * One courier company (docs/modules/COURIER.md §2). Business logic ({@code ShipmentService},
 * {@code CourierWebhookService}) talks only to this interface, with our own types: our statuses,
 * money in poisha. Each implementation maps the courier's own statuses and units.
 *
 * <p><b>Adding a real courier</b> (Steadfast, Pathao): a new {@code CourierCode}, a new class
 * implementing this interface in {@code courier.provider.<name>}, its config properties, its status
 * mapping table and its webhook check. Nothing else changes.
 *
 * <p>Implementations must not throw for "the courier said no" or "the courier did not answer":
 * they return {@code Rejected} / {@code Unavailable}. The services still catch any
 * {@code RuntimeException} and treat it as {@code Unavailable}.
 */
public interface CourierProvider {

    CourierCode code();

    /** Books a parcel. <b>Idempotent on {@code merchantRef}</b>: the same ref again returns the same parcel. */
    BookingResult createShipment(CreateShipmentCommand command);

    /** After a timeout: did the courier create the parcel anyway? Empty = no (or unknown to the courier). */
    Optional<BookingResult.Created> findByMerchantRef(String merchantRef);

    TrackResult track(String consignmentId);

    CancelResult cancel(String consignmentId);

    /**
     * Verifies the request's signature <b>first</b>, then maps the payload.
     *
     * @throws InvalidSignatureException signature missing, wrong or too old (→ 401, same answer for every cause)
     * @throws InvalidWebhookPayloadException signed, but the body cannot be read (→ 400)
     */
    WebhookEvent parseWebhook(HttpHeaders headers, byte[] rawBody);
}

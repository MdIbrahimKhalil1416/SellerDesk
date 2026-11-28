package com.sellerdesk.courier.provider;

/**
 * A verified webhook. Only these fields are taken from the request body; anything else in it
 * (a {@code shopId}, an {@code orderId}) is ignored: the shop always comes from our shipment.
 *
 * @param consignmentId the courier's parcel id (may be missing if the courier only sends our ref)
 * @param merchantRef   our reference (the shipment id), used when the webhook beats our CREATED write
 */
public record WebhookEvent(String consignmentId, String merchantRef, CourierUpdate update) {
}

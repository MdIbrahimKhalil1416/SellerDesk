package com.sellerdesk.courier.provider;

import com.sellerdesk.courier.model.Recipient;

/**
 * What a provider needs to book one parcel.
 *
 * @param merchantRef     our reference; the provider must deduplicate on it
 * @param codAmountPoisha what the rider collects; an adapter converts to taka if its API wants taka
 * @param itemCount       units in the parcel
 */
public record CreateShipmentCommand(String merchantRef, String orderNumber, Recipient recipient,
                                    long codAmountPoisha, String note, int itemCount) {
}

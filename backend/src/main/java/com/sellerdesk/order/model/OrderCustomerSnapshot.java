package com.sellerdesk.order.model;

import com.sellerdesk.common.model.Address;

/**
 * Customer data as it was used for this order: the name and phone to call and the address to
 * deliver to. Later edits of the customer record do not change it.
 *
 * @param phone   {@code 01XXXXXXXXX}, the customer's main phone
 * @param address delivery address; its zone decided the default delivery charge
 */
public record OrderCustomerSnapshot(String name, String phone, Address address) {
}

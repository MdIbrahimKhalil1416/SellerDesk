package com.sellerdesk.courier.model;

import com.sellerdesk.common.model.Address;

/** Snapshot of the order's customer at booking, so a retry sends exactly the same data. */
public record Recipient(String name, String phone, Address address) {
}

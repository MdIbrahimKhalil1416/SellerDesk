package com.sellerdesk.courier.model;

/**
 * The couriers SellerDesk can talk to (docs/modules/COURIER.md §2). Phase 2 has only the mock.
 * A real courier (STEADFAST, PATHAO) is added here together with its {@code CourierProvider}.
 */
public enum CourierCode {
    MOCK("Mock courier");

    private final String displayName;

    CourierCode(String displayName) {
        this.displayName = displayName;
    }

    /** Shown in {@code orders.shipping.courierName} and as the system actor's name. */
    public String displayName() {
        return displayName;
    }
}

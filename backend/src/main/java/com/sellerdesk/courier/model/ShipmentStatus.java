package com.sellerdesk.courier.model;

/**
 * Status of one booking (docs/modules/COURIER.md §3). The allowed moves are in
 * {@code ShipmentStatusMachine}.
 */
public enum ShipmentStatus {
    /** Internal: the provider call is in progress, or it timed out and will be retried. */
    BOOKING(0, true),
    /** The courier accepted the parcel (consignment id known). The order is still PACKED. */
    CREATED(1, true),
    /** The rider has it: the order ships (stock DEDUCT). */
    PICKED_UP(2, true),
    IN_TRANSIT(3, true),
    /** Delivered: the order is delivered automatically. */
    DELIVERED(4, false),
    /** Back at the seller: staff confirm the order's return with restock choices. */
    RETURNED(4, false),
    CANCELLED(-1, false),
    /** The courier refused the booking, or we gave up retrying. */
    FAILED(-1, false);

    private final int rank;
    private final boolean active;

    ShipmentStatus(int rank, boolean active) {
        this.rank = rank;
        this.active = active;
    }

    /** Position on the way to the customer; only forward moves are allowed. */
    public int rank() {
        return rank;
    }

    /** At most one active shipment per order (unique partial index on {@code active: true}). */
    public boolean active() {
        return active;
    }

    public boolean terminal() {
        return !active;
    }
}

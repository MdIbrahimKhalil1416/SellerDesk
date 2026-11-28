package com.sellerdesk.courier.service;

import com.sellerdesk.courier.model.ShipmentStatus;

/**
 * Which shipment status changes are allowed (docs/modules/COURIER.md §3), in one place.
 *
 * <ul>
 *   <li>Forward only, by {@link ShipmentStatus#rank()}: BOOKING 0 &lt; CREATED 1 &lt; PICKED_UP 2 &lt;
 *       IN_TRANSIT 3 &lt; DELIVERED / RETURNED 4. Skipping a step is fine (a lost pickup webhook):
 *       CREATED → IN_TRANSIT counts as a pickup.</li>
 *   <li>CANCELLED only from BOOKING or CREATED (not after pickup).</li>
 *   <li>RETURNED only after pickup (nothing can come back that was never picked up).</li>
 *   <li>FAILED only from BOOKING (the booking never worked).</li>
 *   <li>DELIVERED, RETURNED, CANCELLED, FAILED are final.</li>
 *   <li>The same status again is not a change ({@link Move#SAME}: event only, e.g. a location update).</li>
 * </ul>
 * Anything else is an illegal jump: recorded, ignored, never applied.
 */
public final class ShipmentStatusMachine {

    public enum Move {
        ALLOWED,
        /** No status change: append the event only. */
        SAME,
        ILLEGAL
    }

    private ShipmentStatusMachine() {
    }

    /** @param to {@code null} = the courier reported something that is not a status change */
    public static Move check(ShipmentStatus from, ShipmentStatus to) {
        if (to == null || to == from) {
            return Move.SAME;
        }
        if (from.terminal() || to == ShipmentStatus.BOOKING) {
            return Move.ILLEGAL;
        }
        return switch (to) {
            case FAILED -> from == ShipmentStatus.BOOKING ? Move.ALLOWED : Move.ILLEGAL;
            case CANCELLED -> from == ShipmentStatus.BOOKING || from == ShipmentStatus.CREATED ? Move.ALLOWED : Move.ILLEGAL;
            case RETURNED -> from == ShipmentStatus.PICKED_UP || from == ShipmentStatus.IN_TRANSIT ? Move.ALLOWED : Move.ILLEGAL;
            default -> to.rank() > from.rank() ? Move.ALLOWED : Move.ILLEGAL;
        };
    }
}

package com.sellerdesk.courier.service;

import static com.sellerdesk.courier.model.ShipmentStatus.BOOKING;
import static com.sellerdesk.courier.model.ShipmentStatus.CANCELLED;
import static com.sellerdesk.courier.model.ShipmentStatus.CREATED;
import static com.sellerdesk.courier.model.ShipmentStatus.DELIVERED;
import static com.sellerdesk.courier.model.ShipmentStatus.FAILED;
import static com.sellerdesk.courier.model.ShipmentStatus.IN_TRANSIT;
import static com.sellerdesk.courier.model.ShipmentStatus.PICKED_UP;
import static com.sellerdesk.courier.model.ShipmentStatus.RETURNED;
import static com.sellerdesk.courier.service.ShipmentStatusMachine.Move.ALLOWED;
import static com.sellerdesk.courier.service.ShipmentStatusMachine.Move.ILLEGAL;
import static com.sellerdesk.courier.service.ShipmentStatusMachine.Move.SAME;
import static org.assertj.core.api.Assertions.assertThat;

import com.sellerdesk.courier.model.ShipmentStatus;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Every one of the 8 × 8 status pairs (docs/modules/COURIER.md §3). */
class ShipmentStatusMachineTest {

    /** The only allowed changes. Everything else (except the same status) is illegal. */
    private static final Map<ShipmentStatus, Set<ShipmentStatus>> ALLOWED_MOVES = Map.of(
            BOOKING, EnumSet.of(CREATED, PICKED_UP, IN_TRANSIT, DELIVERED, CANCELLED, FAILED),
            CREATED, EnumSet.of(PICKED_UP, IN_TRANSIT, DELIVERED, CANCELLED),
            PICKED_UP, EnumSet.of(IN_TRANSIT, DELIVERED, RETURNED),
            IN_TRANSIT, EnumSet.of(DELIVERED, RETURNED),
            DELIVERED, EnumSet.noneOf(ShipmentStatus.class),
            RETURNED, EnumSet.noneOf(ShipmentStatus.class),
            CANCELLED, EnumSet.noneOf(ShipmentStatus.class),
            FAILED, EnumSet.noneOf(ShipmentStatus.class));

    @Test
    void allPairs() {
        for (ShipmentStatus from : ShipmentStatus.values()) {
            for (ShipmentStatus to : ShipmentStatus.values()) {
                ShipmentStatusMachine.Move expected = from == to ? SAME
                        : ALLOWED_MOVES.get(from).contains(to) ? ALLOWED : ILLEGAL;
                assertThat(ShipmentStatusMachine.check(from, to)).as(from + " → " + to).isEqualTo(expected);
            }
        }
    }

    @Test
    void noStatusIsAnEventOnly() {
        for (ShipmentStatus from : ShipmentStatus.values()) {
            assertThat(ShipmentStatusMachine.check(from, null)).isEqualTo(SAME);
        }
    }

    @Test
    void typicalIllegalJumps() {
        assertThat(ShipmentStatusMachine.check(DELIVERED, IN_TRANSIT)).isEqualTo(ILLEGAL);
        assertThat(ShipmentStatusMachine.check(CREATED, RETURNED)).isEqualTo(ILLEGAL);
        assertThat(ShipmentStatusMachine.check(CANCELLED, PICKED_UP)).isEqualTo(ILLEGAL);
        assertThat(ShipmentStatusMachine.check(IN_TRANSIT, PICKED_UP)).isEqualTo(ILLEGAL);
        assertThat(ShipmentStatusMachine.check(PICKED_UP, CANCELLED)).isEqualTo(ILLEGAL);
    }

    @Test
    void activeAndTerminalStatuses() {
        assertThat(EnumSet.allOf(ShipmentStatus.class).stream().filter(ShipmentStatus::active))
                .containsExactlyInAnyOrder(BOOKING, CREATED, PICKED_UP, IN_TRANSIT);
    }
}

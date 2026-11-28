package com.sellerdesk.courier.service;

import com.sellerdesk.common.security.SystemActor;
import com.sellerdesk.common.security.SystemActorScope;
import com.sellerdesk.courier.provider.CourierProperties;
import com.sellerdesk.courier.repository.ShipmentRoutingRepository;
import com.sellerdesk.courier.repository.ShipmentRoutingRepository.ShipmentRoute;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The two background jobs of the courier module (docs/modules/COURIER.md §6). Jobs have no
 * logged-in user and no {@code TenantContext}: each shipment is found across all shops
 * ({@link ShipmentRoutingRepository}) and then handled inside its own shop with
 * {@link SystemActorScope}.
 *
 * <ul>
 *   <li><b>Booking reconciler</b> (every minute): finishes bookings whose courier call timed out.</li>
 *   <li><b>Tracking sweep</b> (every hour): polls shipments with no courier news for 12 hours, in
 *       case a webhook was lost.</li>
 * </ul>
 * Off when {@code sellerdesk.courier.jobs-enabled=false} (tests call {@link #reconcileBookings}
 * and {@link #sweepSilentShipments} directly). One failing shipment never stops the others.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourierJobs {

    static final Duration STALE_BOOKING = Duration.ofMinutes(2);
    static final Duration SILENT_FOR = Duration.ofHours(12);
    static final int BOOKINGS_PER_RUN = 50;
    static final int SWEEP_PER_RUN = 200;

    private final ShipmentRoutingRepository routing;
    private final ShipmentService shipmentService;
    private final CourierProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${sellerdesk.courier.reconcile-interval:PT1M}",
            initialDelayString = "${sellerdesk.courier.reconcile-interval:PT1M}")
    void scheduledReconcile() {
        if (properties.jobsEnabled()) {
            reconcileBookings();
        }
    }

    @Scheduled(fixedDelayString = "${sellerdesk.courier.sweep-interval:PT1H}",
            initialDelayString = "${sellerdesk.courier.sweep-interval:PT1H}")
    void scheduledSweep() {
        if (properties.jobsEnabled()) {
            sweepSilentShipments();
        }
    }

    /** @return shipments handled */
    public int reconcileBookings() {
        Instant now = Instant.now(clock);
        List<ShipmentRoute> due = routing.dueBookings(now, now.minus(STALE_BOOKING), BOOKINGS_PER_RUN);
        for (ShipmentRoute route : due) {
            try {
                SystemActorScope.runAs(route.shopId(), SystemActor.system(),
                        () -> shipmentService.retryBooking(route.shipmentId()));
            } catch (RuntimeException e) {
                log.warn("Booking retry failed for shipment {}: {}", route.shipmentId(), e.toString());
            }
        }
        return due.size();
    }

    /** @return shipments polled */
    public int sweepSilentShipments() {
        List<ShipmentRoute> silent = routing.silentActive(Instant.now(clock).minus(SILENT_FOR), SWEEP_PER_RUN);
        for (ShipmentRoute route : silent) {
            try {
                SystemActorScope.runAs(route.shopId(), SystemActor.system(), () -> shipmentService.sweep(route.shipmentId()));
            } catch (RuntimeException e) {
                log.warn("Tracking sweep failed for shipment {}: {}", route.shipmentId(), e.toString());
            }
        }
        return silent.size();
    }
}

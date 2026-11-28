package com.sellerdesk.courier.provider.mock;

import com.sellerdesk.courier.model.CourierCode;
import com.sellerdesk.courier.model.Shipment;
import com.sellerdesk.courier.provider.CourierProperties;
import com.sellerdesk.courier.repository.ShipmentRoutingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The mock keeps its parcels in memory. After a restart this puts the active ones back from our
 * own {@code shipments}, so tracking and the dev simulator keep working. Only when the jobs are on
 * (not in tests, which start without a database).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sellerdesk.courier.mock.enabled", havingValue = "true")
@RequiredArgsConstructor
public class MockCourierStateLoader {

    private final MockCourierProvider mock;
    private final ShipmentRoutingRepository routing;
    private final CourierProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void restore() {
        if (!properties.jobsEnabled()) {
            return;
        }
        try {
            int count = 0;
            for (Shipment s : routing.activeWithConsignment(CourierCode.MOCK)) {
                mock.restore(s.getMerchantRef(), s.getConsignmentId(), s.getTrackingCode(), s.getCodAmount(),
                        MockCourierProvider.mockStatusOf(s.getStatus()));
                count++;
            }
            log.info("Mock courier: restored {} active parcel(s)", count);
        } catch (RuntimeException e) {
            log.warn("Mock courier: could not restore parcels: {}", e.toString());
        }
    }
}

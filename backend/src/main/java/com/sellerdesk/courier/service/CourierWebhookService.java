package com.sellerdesk.courier.service;

import com.sellerdesk.common.error.AppException;
import com.sellerdesk.courier.dto.WebhookAck;
import com.sellerdesk.courier.model.CourierCode;
import com.sellerdesk.courier.model.EventSource;
import com.sellerdesk.courier.provider.CourierProvider;
import com.sellerdesk.courier.provider.CourierProviderRegistry;
import com.sellerdesk.courier.provider.InvalidSignatureException;
import com.sellerdesk.courier.provider.WebhookEvent;
import com.sellerdesk.courier.repository.ShipmentRoutingRepository;
import com.sellerdesk.courier.repository.ShipmentRoutingRepository.ShipmentRoute;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

/**
 * Courier webhooks (docs/modules/COURIER.md §5). Called without any logged-in user, so
 * {@code TenantContext} is empty here. The order of the steps is the security:
 * <ol>
 *   <li><b>Verify</b> the signature before reading anything from the body or the database.</li>
 *   <li><b>Route</b>: find the shipment by the courier's consignment id (or our merchant
 *       reference) and take the shop <b>from our own shipment record</b>. A {@code shopId} or
 *       {@code orderId} in the body is never read.</li>
 *   <li><b>Apply</b> inside that shop as the courier ({@link ShipmentEventApplier}).</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourierWebhookService {

    private final CourierProviderRegistry providers;
    private final ShipmentRoutingRepository routing;
    private final ShipmentEventApplier applier;

    public WebhookAck handle(String providerPath, HttpHeaders headers, byte[] body) {
        CourierCode code = parseProvider(providerPath);
        CourierProvider provider;
        try {
            provider = providers.get(code);
        } catch (AppException e) {
            throw CourierErrors.shipmentNotFound(); // a disabled courier looks like an unknown path
        }

        WebhookEvent event;
        try {
            event = provider.parseWebhook(headers, body);
        } catch (InvalidSignatureException e) {
            // The cause is only logged; the caller always gets the same 401. Never log the body or signature.
            log.warn("Courier webhook rejected: provider={}, reason={}", code, e.logReason());
            throw e;
        }

        ShipmentRoute route = routing.byConsignment(code, event.consignmentId())
                .or(() -> routing.byMerchantRef(code, event.merchantRef()))
                .orElseThrow(() -> {
                    log.info("Courier webhook for unknown shipment: provider={}, consignment={}", code, event.consignmentId());
                    return CourierErrors.shipmentNotFound();
                });

        WebhookAck.Result result = applier.apply(route.shopId(), code, route.shipmentId(), event.consignmentId(),
                event.update(), EventSource.WEBHOOK);
        log.info("Courier webhook: provider={}, shipment={}, courierStatus={}, result={}", code, route.shipmentId(),
                event.update().providerStatus(), result);
        return new WebhookAck(result);
    }

    private static CourierCode parseProvider(String path) {
        try {
            return CourierCode.valueOf(path.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw CourierErrors.shipmentNotFound();
        }
    }
}

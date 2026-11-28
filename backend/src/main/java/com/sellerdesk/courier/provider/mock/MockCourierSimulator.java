package com.sellerdesk.courier.provider.mock;

import com.sellerdesk.courier.provider.CourierProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev only: plays the courier. Every tick it moves each mock parcel one step
 * ({@code booked → picked_up → in_transit → delivered}, some end {@code returned}) and sends a
 * <b>signed webhook over HTTP to our own endpoint</b>, so the real verification and processing path
 * runs in dev, not a shortcut (docs/modules/COURIER.md §2).
 *
 * <p>On only with {@code sellerdesk.courier.mock.simulator.enabled=true} (application-dev.yml) and a
 * webhook secret.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = {"sellerdesk.courier.mock.enabled", "sellerdesk.courier.mock.simulator.enabled"},
        havingValue = "true")
public class MockCourierSimulator {

    private final MockCourierProvider mock;
    private final CourierProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public MockCourierSimulator(MockCourierProvider mock, CourierProperties properties, ObjectMapper objectMapper,
                                Clock clock) {
        this.mock = mock;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${sellerdesk.courier.mock.simulator.interval:PT30S}",
            initialDelayString = "${sellerdesk.courier.mock.simulator.interval:PT30S}")
    public void tick() {
        if (!properties.hasWebhookSecret()) {
            log.debug("Mock courier simulator: no COURIER_WEBHOOK_SECRET, not sending webhooks");
            return;
        }
        for (MockCourierProvider.Parcel parcel : mock.parcels()) {
            String next = next(parcel.status());
            if (next == null) {
                continue;
            }
            mock.advance(parcel.consignmentId(), next);
            send(parcel, next);
        }
    }

    private String next(String status) {
        return switch (status) {
            case "booked" -> "picked_up";
            case "picked_up" -> "in_transit";
            case "in_transit" -> ThreadLocalRandom.current().nextInt(100) < properties.mock().simulator().returnPercent()
                    ? "returned" : "delivered";
            default -> null; // delivered, returned, cancelled: final
        };
    }

    private void send(MockCourierProvider.Parcel parcel, String status) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventId", UUID.randomUUID().toString());
            payload.put("consignmentId", parcel.consignmentId());
            payload.put("merchantRef", parcel.merchantRef());
            payload.put("status", status);
            payload.put("time", Instant.now(clock).toString());
            if ("delivered".equals(status)) {
                payload.put("collectedAmount", parcel.codAmount());
            }
            if ("delivered".equals(status) || "returned".equals(status)) {
                payload.put("courierFee", MockCourierProvider.FEE_POISHA);
            }
            byte[] body = objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
            long ts = Instant.now(clock).getEpochSecond();
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.mock().simulator().baseUrl()
                            + "/api/v1/webhooks/courier/mock"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header(WebhookSigner.TIMESTAMP_HEADER, Long.toString(ts))
                    .header(WebhookSigner.SIGNATURE_HEADER, WebhookSigner.sign(properties.webhookSecret(), ts, body))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            log.debug("Mock courier simulator: {} {} → {}", parcel.consignmentId(), status, response.statusCode());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Mock courier simulator could not send a webhook: {}", e.toString());
        }
    }
}

package com.sellerdesk.courier.provider.mock;

import com.sellerdesk.courier.model.CourierCode;
import com.sellerdesk.courier.model.ShipmentStatus;
import com.sellerdesk.courier.provider.BookingResult;
import com.sellerdesk.courier.provider.CancelResult;
import com.sellerdesk.courier.provider.CourierProperties;
import com.sellerdesk.courier.provider.CourierProperties.MockMode;
import com.sellerdesk.courier.provider.CourierProvider;
import com.sellerdesk.courier.provider.CourierUpdate;
import com.sellerdesk.courier.provider.CreateShipmentCommand;
import com.sellerdesk.courier.provider.InvalidWebhookPayloadException;
import com.sellerdesk.courier.provider.TrackResult;
import com.sellerdesk.courier.provider.WebhookEvent;
import java.io.UncheckedIOException;
import java.net.http.HttpTimeoutException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A pretend courier for development and tests (docs/modules/COURIER.md §2). It behaves like a real
 * one where it matters: consignment ids, idempotency on {@code merchantRef}, its own status names,
 * signed webhooks, and failures on demand.
 *
 * <ul>
 *   <li>Consignment id {@code MOCK-} + 10 digits, tracking code {@code MCK} + 8 characters.</li>
 *   <li>Its own status names (like a real courier): {@code booked, picked_up, in_transit,
 *       delivered, returned, cancelled}, plus {@code location_update} (no status change). They are
 *       mapped to our statuses in {@link #STATUS_MAP}, as every real adapter must do.</li>
 *   <li>Failures: {@code sellerdesk.courier.mock.mode} for every call, or {@link #nextCalls} for the
 *       next calls only (tests). {@code TIMEOUT} creates the parcel and then throws, like a response
 *       that got lost on the way back: exactly the case the {@code BOOKING} status exists for.</li>
 * </ul>
 * Parcels live in memory. {@code MockCourierStateLoader} puts the active ones back after a restart.
 */
@Component
@ConditionalOnProperty(name = "sellerdesk.courier.mock.enabled", havingValue = "true")
public class MockCourierProvider implements CourierProvider {

    /** The mock's status names → ours. {@code null} = the event changes no status. */
    static final Map<String, Optional<ShipmentStatus>> STATUS_MAP = Map.of(
            "booked", Optional.of(ShipmentStatus.CREATED),
            "picked_up", Optional.of(ShipmentStatus.PICKED_UP),
            "in_transit", Optional.of(ShipmentStatus.IN_TRANSIT),
            "location_update", Optional.empty(),
            "delivered", Optional.of(ShipmentStatus.DELIVERED),
            "returned", Optional.of(ShipmentStatus.RETURNED),
            "cancelled", Optional.of(ShipmentStatus.CANCELLED));

    /** What the mock charges per parcel: ৳60. */
    public static final long FEE_POISHA = 6_000;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** One parcel at the "courier". */
    public static final class Parcel {
        private final String merchantRef;
        private final String consignmentId;
        private final String trackingCode;
        private final long codAmount;
        private volatile String status = "booked";
        private volatile Instant updatedAt;
        private volatile Long collectedAmount;

        Parcel(String merchantRef, String consignmentId, String trackingCode, long codAmount, Instant now) {
            this.merchantRef = merchantRef;
            this.consignmentId = consignmentId;
            this.trackingCode = trackingCode;
            this.codAmount = codAmount;
            this.updatedAt = now;
        }

        public String merchantRef() {
            return merchantRef;
        }

        public String consignmentId() {
            return consignmentId;
        }

        public String trackingCode() {
            return trackingCode;
        }

        public long codAmount() {
            return codAmount;
        }

        public String status() {
            return status;
        }

        public Instant updatedAt() {
            return updatedAt;
        }
    }

    private final Map<String, Parcel> byMerchantRef = new ConcurrentHashMap<>();
    private final Map<String, Parcel> byConsignment = new ConcurrentHashMap<>();
    private final Deque<MockMode> nextCalls = new ArrayDeque<>();
    private final AtomicLong flakyCounter = new AtomicLong();
    private final CourierProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MockCourierProvider(CourierProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public CourierCode code() {
        return CourierCode.MOCK;
    }

    @Override
    public BookingResult createShipment(CreateShipmentCommand command) {
        MockMode mode = nextMode();
        if (mode == MockMode.REJECT) {
            return new BookingResult.Rejected("Mock courier: the recipient's phone number is not valid",
                    Map.of("recipient.phone", "not accepted by the courier"));
        }
        // Idempotent on merchantRef: the same booking again is the same parcel.
        Parcel parcel = byMerchantRef.computeIfAbsent(command.merchantRef(), ref -> {
            Parcel p = new Parcel(ref, newConsignmentId(), newTrackingCode(), command.codAmountPoisha(), Instant.now(clock));
            byConsignment.put(p.consignmentId, p);
            return p;
        });
        if (mode == MockMode.TIMEOUT) {
            // The parcel now exists at the courier, but our side never hears about it.
            throw timeout();
        }
        return new BookingResult.Created(parcel.consignmentId, parcel.trackingCode);
    }

    @Override
    public Optional<BookingResult.Created> findByMerchantRef(String merchantRef) {
        if (nextMode() == MockMode.TIMEOUT) {
            throw timeout();
        }
        return Optional.ofNullable(byMerchantRef.get(merchantRef))
                .map(p -> new BookingResult.Created(p.consignmentId, p.trackingCode));
    }

    @Override
    public TrackResult track(String consignmentId) {
        MockMode mode = nextMode();
        if (mode == MockMode.TIMEOUT) {
            throw timeout();
        }
        Parcel p = byConsignment.get(consignmentId);
        if (p == null || mode == MockMode.REJECT) {
            return new TrackResult.Rejected("Mock courier: unknown consignment");
        }
        return new TrackResult.Ok(new CourierUpdate(map(p.status), p.status, null, p.updatedAt,
                "delivered".equals(p.status) ? p.collectedAmount : null,
                "delivered".equals(p.status) || "returned".equals(p.status) ? FEE_POISHA : null, null));
    }

    @Override
    public CancelResult cancel(String consignmentId) {
        MockMode mode = nextMode();
        if (mode == MockMode.TIMEOUT) {
            throw timeout();
        }
        Parcel p = byConsignment.get(consignmentId);
        if (p == null || mode == MockMode.REJECT) {
            return new CancelResult.Rejected("Mock courier: unknown consignment");
        }
        synchronized (p) {
            if ("booked".equals(p.status) || "cancelled".equals(p.status)) {
                p.status = "cancelled";
                p.updatedAt = Instant.now(clock);
                return new CancelResult.Ok();
            }
        }
        return new CancelResult.Rejected("Mock courier: the parcel was already picked up");
    }

    @Override
    public WebhookEvent parseWebhook(HttpHeaders headers, byte[] rawBody) {
        // Signature first: nothing of the body is read before it is proven to come from the courier.
        WebhookSigner.verify(properties.webhookSecret(), headers, rawBody, clock);
        JsonNode json;
        try {
            json = objectMapper.readTree(rawBody);
        } catch (JacksonException e) {
            throw new InvalidWebhookPayloadException("Webhook body is not valid JSON");
        }
        String status = text(json, "status");
        if (status == null || (text(json, "consignmentId") == null && text(json, "merchantRef") == null)) {
            throw new InvalidWebhookPayloadException("Webhook needs status and consignmentId or merchantRef");
        }
        Instant time = null;
        if (text(json, "time") != null) {
            try {
                time = Instant.parse(text(json, "time"));
            } catch (DateTimeParseException e) {
                throw new InvalidWebhookPayloadException("time must be an ISO-8601 instant");
            }
        }
        CourierUpdate update = new CourierUpdate(map(status), truncate(status, 50), text(json, "eventId"), time,
                number(json, "collectedAmount"), number(json, "courierFee"), truncate(text(json, "note"), 300));
        // Any shopId / orderId in the body is deliberately not read.
        return new WebhookEvent(text(json, "consignmentId"), text(json, "merchantRef"), update);
    }

    // ==== For the simulator and tests ======================================================

    /** The next calls answer with these modes (one per call), then the configured mode again. */
    public void nextCalls(MockMode... modes) {
        synchronized (nextCalls) {
            nextCalls.addAll(List.of(modes));
        }
    }

    /** Moves a parcel at the "courier", e.g. {@code picked_up}. Delivered parcels collect their COD amount. */
    public void advance(String consignmentId, String mockStatus) {
        advance(consignmentId, mockStatus, null);
    }

    public void advance(String consignmentId, String mockStatus, Long collectedAmount) {
        Parcel p = byConsignment.get(consignmentId);
        if (p == null) {
            throw new IllegalArgumentException("Unknown mock consignment " + consignmentId);
        }
        p.status = mockStatus;
        p.updatedAt = Instant.now(clock);
        if ("delivered".equals(mockStatus)) {
            p.collectedAmount = collectedAmount != null ? collectedAmount : p.codAmount;
        }
    }

    public Optional<Parcel> parcel(String consignmentId) {
        return Optional.ofNullable(byConsignment.get(consignmentId));
    }

    public List<Parcel> parcels() {
        return List.copyOf(byMerchantRef.values());
    }

    /** Puts a parcel back after a restart (from our own {@code shipments}). */
    public void restore(String merchantRef, String consignmentId, String trackingCode, long codAmount,
                        String mockStatus) {
        Parcel p = new Parcel(merchantRef, consignmentId, trackingCode, codAmount, Instant.now(clock));
        p.status = mockStatus;
        if (byMerchantRef.putIfAbsent(merchantRef, p) == null) {
            byConsignment.put(consignmentId, p);
        }
    }

    /** Forgets every parcel and queued mode (tests). */
    public void reset() {
        byMerchantRef.clear();
        byConsignment.clear();
        synchronized (nextCalls) {
            nextCalls.clear();
        }
        flakyCounter.set(0);
    }

    /** Our status → the mock's status name (for {@link #restore}). */
    public static String mockStatusOf(ShipmentStatus status) {
        return switch (status) {
            case PICKED_UP -> "picked_up";
            case IN_TRANSIT -> "in_transit";
            case DELIVERED -> "delivered";
            case RETURNED -> "returned";
            case CANCELLED -> "cancelled";
            default -> "booked";
        };
    }

    // ==== Helpers ==========================================================================

    private MockMode nextMode() {
        MockMode queued;
        synchronized (nextCalls) {
            queued = nextCalls.pollFirst();
        }
        MockMode mode = queued != null ? queued : properties.mock().mode();
        if (mode == MockMode.FLAKY) {
            return flakyCounter.incrementAndGet() % 2 == 0 ? MockMode.TIMEOUT : MockMode.OK;
        }
        return mode;
    }

    private static ShipmentStatus map(String mockStatus) {
        Optional<ShipmentStatus> mapped = STATUS_MAP.get(mockStatus);
        // Unknown status: no change, event only; never guessed.
        return mapped == null ? null : mapped.orElse(null);
    }

    private static UncheckedIOException timeout() {
        return new UncheckedIOException(new HttpTimeoutException("Mock courier: request timed out"));
    }

    private static String newConsignmentId() {
        StringBuilder sb = new StringBuilder("MOCK-");
        for (int i = 0; i < 10; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    private static String newTrackingCode() {
        StringBuilder sb = new StringBuilder("MCK");
        for (int i = 0; i < 8; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    private static String text(JsonNode json, String field) {
        JsonNode node = json.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asString().trim();
        return value.isEmpty() ? null : value;
    }

    private static Long number(JsonNode json, String field) {
        JsonNode node = json.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || node.asLong() < 0) {
            throw new InvalidWebhookPayloadException(field + " must be a whole number of poisha");
        }
        return node.asLong();
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}

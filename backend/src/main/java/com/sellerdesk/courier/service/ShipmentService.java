package com.sellerdesk.courier.service;

import com.sellerdesk.auth.model.User;
import com.sellerdesk.auth.service.UserService;
import com.sellerdesk.common.dto.PageResponse;
import com.sellerdesk.common.error.AppException;
import com.sellerdesk.common.error.FieldValidationException;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.courier.dto.BulkShipmentRequest;
import com.sellerdesk.courier.dto.BulkShipmentResponse;
import com.sellerdesk.courier.dto.SendShipmentRequest;
import com.sellerdesk.courier.dto.ShipmentResponse;
import com.sellerdesk.courier.dto.ShipmentSummary;
import com.sellerdesk.courier.model.CourierCode;
import com.sellerdesk.courier.model.EventSource;
import com.sellerdesk.courier.model.Recipient;
import com.sellerdesk.courier.model.Shipment;
import com.sellerdesk.courier.model.ShipmentEvent;
import com.sellerdesk.courier.model.ShipmentStatus;
import com.sellerdesk.courier.provider.BookingResult;
import com.sellerdesk.courier.provider.CancelResult;
import com.sellerdesk.courier.provider.CourierProvider;
import com.sellerdesk.courier.provider.CourierProviderRegistry;
import com.sellerdesk.courier.provider.CreateShipmentCommand;
import com.sellerdesk.courier.provider.TrackResult;
import com.sellerdesk.courier.repository.ShipmentQueryRepository;
import com.sellerdesk.courier.repository.ShipmentRepository;
import com.sellerdesk.order.model.OrderStatus;
import com.sellerdesk.order.service.OrderForCourier;
import com.sellerdesk.order.service.OrderService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Courier bookings (docs/modules/COURIER.md §4, §6).
 *
 * <p><b>Rules every method follows</b>
 * <ol>
 *   <li>The shop is always {@link CurrentUser#shopId()} (the JWT, or the shipment's shop inside a
 *       job). Another shop's order or shipment is "not found" (404).</li>
 *   <li><b>No provider call inside a database transaction</b>: a courier can take seconds. The writes
 *       before and after the call are single-document updates that state the status they expect.</li>
 *   <li>The order is never changed here. Only a courier status (webhook / poll) changes the order,
 *       through {@link ShipmentEventApplier} and {@code OrderService}.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentService {

    /** Total booking calls (first + 5 retries) before giving up. */
    static final int MAX_BOOKING_ATTEMPTS = 6;

    /** Pause after booking attempt n (1-based) timed out. */
    static final List<Duration> RETRY_DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(2),
            Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(30));

    /** The track endpoint calls the courier at most once per shipment in this time. */
    static final Duration TRACK_THROTTLE = Duration.ofSeconds(60);

    static final String COURIER_TIMEOUT = "COURIER_TIMEOUT";

    private final ShipmentRepository repository;
    private final ShipmentQueryRepository queryRepository;
    private final ShipmentMapper mapper;
    private final ShipmentEventApplier applier;
    private final CourierProviderRegistry providers;
    private final OrderService orderService;
    private final UserService userService;
    private final CurrentUser currentUser;
    private final Clock clock;

    /** @param status 201 booked, 202 still booking (courier did not answer), 200 already booked */
    public record SendResult(ShipmentResponse shipment, HttpStatus status) {
    }

    // ==== Read ===========================================================================

    public ShipmentResponse get(String shipmentId) {
        return mapper.toResponse(load(currentUser.shopId(), shipmentId));
    }

    /** All bookings of an order, newest first. Unknown / other shop's order → 404. */
    public List<ShipmentSummary> forOrder(String orderId) {
        String shopId = currentUser.shopId();
        orderService.forCourier(orderId); // 404 for an unknown or foreign order
        return repository.findByShopIdAndOrderIdOrderByCreatedAtDesc(shopId, orderId).stream()
                .map(ShipmentMapper::toSummary).toList();
    }

    public PageResponse<ShipmentSummary> list(String status, String provider, int page, int size) {
        ShipmentQueryRepository.ListResult result = queryRepository.list(currentUser.shopId(),
                parseEnum(ShipmentStatus.class, status, "status"), parseEnum(CourierCode.class, provider, "provider"),
                page, size);
        return PageResponse.of(result.content().stream().map(ShipmentMapper::toSummary).toList(), page, size,
                result.totalElements());
    }

    // ==== Send ===========================================================================

    /**
     * Books a PACKED order with a courier (docs/modules/COURIER.md §6):
     * <ol>
     *   <li>Checks, then insert a {@code BOOKING} shipment. The unique index allows one active
     *       shipment per order, so two parallel sends create one.</li>
     *   <li>Call the courier, outside any transaction.</li>
     *   <li>Store the outcome with a conditional update on {@code status: BOOKING}.</li>
     * </ol>
     * The order itself stays PACKED; it ships when the courier reports the pickup.
     */
    public SendResult send(String orderId, SendShipmentRequest request) {
        String shopId = currentUser.shopId();
        CourierProvider provider = providers.get(request.provider());
        OrderForCourier order = orderService.forCourier(orderId);
        if (order.status() != OrderStatus.PACKED) {
            throw CourierErrors.orderNotPacked(order.status());
        }
        Optional<Shipment> active = repository.findByShopIdAndOrderIdAndActiveTrue(shopId, orderId);
        if (active.isPresent()) {
            return existing(active.get(), request.provider());
        }

        Instant now = Instant.now(clock);
        String userId = currentUser.userId();
        Shipment s = new Shipment();
        s.setId(new ObjectId().toHexString());
        s.setShopId(shopId);
        s.setOrderId(orderId);
        s.setOrderNumber(order.orderNumber());
        s.setProvider(request.provider());
        s.setMerchantRef(s.getId());
        s.setStatus(ShipmentStatus.BOOKING);
        s.setActive(true);
        s.setCodAmount(order.codAmount());
        s.setRecipient(new Recipient(order.customerName(), order.customerPhone(), order.address()));
        s.setItemCount(order.itemCount());
        s.setNote(text(request.note()));
        s.setCreatedBy(userId);
        s.setCreatedByName(userName(shopId, userId));
        s.getEvents().add(new ShipmentEvent(now, EventSource.USER, ShipmentStatus.BOOKING, null, null,
                "send:" + s.getId(), false, "Sent to " + request.provider().displayName() + " by " + s.getCreatedByName()));
        Shipment booked;
        try {
            booked = repository.insert(s);
        } catch (DuplicateKeyException e) {
            // A parallel send for the same order won the unique index.
            return existing(repository.findByShopIdAndOrderIdAndActiveTrue(shopId, orderId).orElseThrow(() -> e),
                    request.provider());
        }

        // Someone cancelled the order between our check and the insert: undo our booking.
        OrderStatus statusNow = orderService.forCourier(orderId).status();
        if (statusNow != OrderStatus.PACKED) {
            cancelLocally(shopId, booked.getId(), "The order changed to " + statusNow + " while booking");
            throw CourierErrors.orderNotPacked(statusNow);
        }

        BookingResult result = call(() -> provider.createShipment(command(booked)), BookingResult.Unavailable::new);
        Shipment after = applyBookingOutcome(shopId, booked, provider, result, 1);
        return switch (after.getStatus()) {
            case BOOKING -> new SendResult(mapper.toResponse(after), HttpStatus.ACCEPTED);
            case FAILED -> throw result instanceof BookingResult.Rejected r
                    ? CourierErrors.rejected(r.reason(), r.fieldHints())
                    : CourierErrors.unavailable();
            default -> new SendResult(mapper.toResponse(after), HttpStatus.CREATED);
        };
    }

    /**
     * Many orders, one after the other, each exactly like {@link #send}. One failure never stops
     * the others; another shop's or a malformed id is reported as {@code NOT_FOUND}.
     */
    public BulkShipmentResponse bulk(BulkShipmentRequest request) {
        if (new HashSet<>(request.orderIds()).size() != request.orderIds().size()) {
            throw new FieldValidationException("orderIds", "must not contain the same order twice");
        }
        providers.get(request.provider()); // 400 for the whole request if the courier is not available
        List<BulkShipmentResponse.Result> results = new ArrayList<>();
        for (String orderId : request.orderIds()) {
            try {
                ShipmentResponse s = send(orderId, new SendShipmentRequest(request.provider(), null)).shipment();
                results.add(new BulkShipmentResponse.Result(orderId, s.orderNumber(), true, s.id(), s.status(), null, null));
            } catch (AppException e) {
                results.add(new BulkShipmentResponse.Result(orderId, null, false, null, null, e.getCode(), e.getMessage()));
            }
        }
        int ok = (int) results.stream().filter(BulkShipmentResponse.Result::ok).count();
        return new BulkShipmentResponse(request.provider(), ok, results.size() - ok, results);
    }

    // ==== Booking outcome, retries ======================================================

    /**
     * Stores the result of a booking call. Always a conditional update on {@code status: BOOKING}:
     * if the user cancelled meanwhile, the result is dropped (and a parcel the courier created
     * anyway is cancelled there).
     *
     * @param attempts booking calls made so far, including this one
     */
    Shipment applyBookingOutcome(String shopId, Shipment s, CourierProvider provider, BookingResult result,
                                 int attempts) {
        Instant now = Instant.now(clock);
        Update update = new Update().set("updatedAt", now).set("bookingAttempts", attempts);
        switch (result) {
            case BookingResult.Created c -> {
                update.set("status", ShipmentStatus.CREATED).set("consignmentId", c.consignmentId())
                        .set("trackingCode", c.trackingCode()).set("lastEventAt", now).unset("nextAttemptAt");
                ShipmentQueryRepository.pushEvent(update, systemEvent(now, ShipmentStatus.CREATED,
                        "booked:" + c.consignmentId(), "Booked: consignment " + c.consignmentId()));
                if (!queryRepository.updateIf(shopId, s.getId(), ShipmentStatus.BOOKING, null, update)) {
                    return lateBooking(shopId, s.getId(), provider, c);
                }
            }
            case BookingResult.Rejected r -> {
                update.set("status", ShipmentStatus.FAILED).set("active", false).set("failureReason", text(r.reason()))
                        .unset("nextAttemptAt");
                ShipmentQueryRepository.pushEvent(update, systemEvent(now, ShipmentStatus.FAILED,
                        "rejected:" + attempts, "The courier refused: " + r.reason()));
                queryRepository.updateIf(shopId, s.getId(), ShipmentStatus.BOOKING, null, update);
            }
            case BookingResult.Unavailable u -> {
                if (attempts >= MAX_BOOKING_ATTEMPTS) {
                    update.set("status", ShipmentStatus.FAILED).set("active", false)
                            .set("failureReason", COURIER_TIMEOUT).set("needsManualCheck", true).unset("nextAttemptAt");
                    ShipmentQueryRepository.pushEvent(update, systemEvent(now, ShipmentStatus.FAILED, "gaveup:" + attempts,
                            "No answer from the courier after " + attempts + " attempts. Check the courier's panel "
                                    + "before sending again: the parcel may exist there"));
                } else {
                    update.set("nextAttemptAt", now.plus(RETRY_DELAYS.get(attempts - 1)));
                    ShipmentQueryRepository.pushEvent(update, systemEvent(now, null, "timeout:" + attempts,
                            "The courier did not answer (attempt " + attempts + "); SellerDesk will retry"));
                }
                log.info("Courier {} unavailable for shipment {} (attempt {}): {}", provider.code(), s.getId(), attempts,
                        u.cause());
                queryRepository.updateIf(shopId, s.getId(), ShipmentStatus.BOOKING, null, update);
            }
        }
        return load(shopId, s.getId());
    }

    /**
     * The booking result came after the shipment left BOOKING: the user cancelled it, or a
     * webhook was faster than our write.
     */
    private Shipment lateBooking(String shopId, String shipmentId, CourierProvider provider, BookingResult.Created c) {
        Shipment current = load(shopId, shipmentId);
        if (current.getStatus() == ShipmentStatus.CANCELLED) {
            CancelResult cancelled = call(() -> provider.cancel(c.consignmentId()), CancelResult.Unavailable::new);
            log.info("Booking of cancelled shipment {} arrived late; cancelled at the courier: {}", shipmentId, cancelled);
        } else if (current.getTrackingCode() == null) {
            queryRepository.updateIf(shopId, shipmentId, null, null, new Update()
                    .set("consignmentId", c.consignmentId()).set("trackingCode", c.trackingCode()));
            current = load(shopId, shipmentId);
        }
        return current;
    }

    /**
     * One retry of a BOOKING shipment (booking reconciler). First asks the courier whether the
     * timed-out call created the parcel anyway; only if not, books again with the <b>same</b>
     * {@code merchantRef}, so the courier can never end up with two parcels.
     */
    public void retryBooking(String shipmentId) {
        String shopId = currentUser.shopId();
        Shipment s = load(shopId, shipmentId);
        if (s.getStatus() != ShipmentStatus.BOOKING) {
            return;
        }
        CourierProvider provider = providers.get(s.getProvider());
        Optional<BookingResult.Created> found;
        try {
            found = provider.findByMerchantRef(s.getMerchantRef());
        } catch (RuntimeException e) {
            applyBookingOutcome(shopId, s, provider, new BookingResult.Unavailable(e.toString()), s.getBookingAttempts() + 1);
            return;
        }
        if (found.isPresent()) {
            applyBookingOutcome(shopId, s, provider, found.get(), Math.max(1, s.getBookingAttempts()));
            return;
        }
        BookingResult result = call(() -> provider.createShipment(command(s)), BookingResult.Unavailable::new);
        applyBookingOutcome(shopId, s, provider, result, s.getBookingAttempts() + 1);
    }

    // ==== Cancel =========================================================================

    /**
     * Cancels a booking before pickup. The order stays PACKED and can be sent again.
     * Cancelling a cancelled shipment again is harmless (200).
     */
    public ShipmentResponse cancel(String shipmentId, String reason) {
        String shopId = currentUser.shopId();
        Shipment s = load(shopId, shipmentId);
        String why = text(reason);
        switch (s.getStatus()) {
            case CANCELLED -> {
                return mapper.toResponse(s);
            }
            case BOOKING -> {
                if (!cancelLocally(shopId, shipmentId, why)) {
                    return cancel(shipmentId, reason); // the booking finished meanwhile: decide again
                }
            }
            case CREATED -> {
                CourierProvider provider = providers.get(s.getProvider());
                CancelResult result = call(() -> provider.cancel(s.getConsignmentId()), CancelResult.Unavailable::new);
                switch (result) {
                    case CancelResult.Unavailable u -> throw CourierErrors.unavailable();
                    case CancelResult.Rejected r -> throw CourierErrors.notCancellable(s.getStatus(), "The courier refused: " + r.reason());
                    case CancelResult.Ok ok -> {
                        Instant now = Instant.now(clock);
                        Update update = cancelUpdate(now, why);
                        if (!queryRepository.updateIf(shopId, shipmentId, ShipmentStatus.CREATED, null, update)) {
                            Shipment current = load(shopId, shipmentId);
                            if (current.getStatus() != ShipmentStatus.CANCELLED) {
                                throw CourierErrors.notCancellable(current.getStatus(), null);
                            }
                        }
                    }
                }
            }
            default -> throw CourierErrors.notCancellable(s.getStatus(), null);
        }
        return mapper.toResponse(load(shopId, shipmentId));
    }

    private boolean cancelLocally(String shopId, String shipmentId, String reason) {
        return queryRepository.updateIf(shopId, shipmentId, ShipmentStatus.BOOKING, null,
                cancelUpdate(Instant.now(clock), reason));
    }

    private Update cancelUpdate(Instant now, String reason) {
        Update update = new Update().set("status", ShipmentStatus.CANCELLED).set("active", false)
                .set("cancelReason", reason).set("updatedAt", now).unset("nextAttemptAt");
        String by = currentUser.systemActor().isPresent() ? "SellerDesk" : userName(currentUser.shopId(), currentUser.userId());
        return ShipmentQueryRepository.pushEvent(update, new ShipmentEvent(now, EventSource.USER, ShipmentStatus.CANCELLED,
                null, null, "cancel:" + new ObjectId().toHexString(), false, "Cancelled by " + by + ": " + reason));
    }

    // ==== Track ==========================================================================

    /**
     * Asks the courier for the current status and applies it like a webhook (source POLL). At most
     * one courier call per shipment per minute; final and not-yet-booked shipments need no call.
     */
    public ShipmentResponse track(String shipmentId) {
        String shopId = currentUser.shopId();
        Shipment s = load(shopId, shipmentId);
        Instant now = Instant.now(clock);
        if (!s.getStatus().active() || s.getConsignmentId() == null
                || (s.getLastTrackedAt() != null && s.getLastTrackedAt().plus(TRACK_THROTTLE).isAfter(now))) {
            return mapper.toResponse(s);
        }
        poll(shopId, s, EventSource.POLL);
        return mapper.toResponse(load(shopId, shipmentId));
    }

    /**
     * Tracking sweep: polls a shipment that has been silent for hours. A courier that does not
     * answer is simply asked again next time.
     */
    public void sweep(String shipmentId) {
        String shopId = currentUser.shopId();
        Shipment s = load(shopId, shipmentId);
        if (!s.getStatus().active() || s.getConsignmentId() == null) {
            return;
        }
        try {
            poll(shopId, s, EventSource.POLL);
        } catch (AppException e) {
            log.info("Tracking sweep: shipment {} not updated: {}", shipmentId, e.getCode());
        }
    }

    private void poll(String shopId, Shipment s, EventSource source) {
        CourierProvider provider = providers.get(s.getProvider());
        TrackResult result = call(() -> provider.track(s.getConsignmentId()), TrackResult.Unavailable::new);
        Instant now = Instant.now(clock);
        switch (result) {
            case TrackResult.Unavailable u -> throw CourierErrors.unavailable();
            case TrackResult.Rejected r -> {
                queryRepository.updateIf(shopId, s.getId(), null, null, new Update().set("lastTrackedAt", now));
                log.info("Courier refused tracking of shipment {}: {}", s.getId(), r.reason());
            }
            case TrackResult.Ok ok -> {
                // The courier answered: no need to ask again for a while, even if nothing changed.
                queryRepository.updateIf(shopId, s.getId(), null, null,
                        new Update().set("lastTrackedAt", now).set("lastEventAt", now));
                applier.apply(shopId, s.getProvider(), s.getId(), s.getConsignmentId(), ok.update(), source);
            }
        }
    }

    // ==== Helpers ========================================================================

    private SendResult existing(Shipment active, CourierCode requested) {
        if (active.getProvider() != requested) {
            throw CourierErrors.alreadyActive(active.getProvider());
        }
        return new SendResult(mapper.toResponse(active), HttpStatus.OK);
    }

    private static CreateShipmentCommand command(Shipment s) {
        return new CreateShipmentCommand(s.getMerchantRef(), s.getOrderNumber(), s.getRecipient(), s.getCodAmount(),
                s.getNote(), s.getItemCount());
    }

    /**
     * A provider call. Any exception (timeout, connection refused, a bug in an adapter) counts as
     * "the courier did not answer": we cannot know what happened on its side.
     */
    private <T> T call(Supplier<T> work, java.util.function.Function<String, T> unavailable) {
        try {
            return work.get();
        } catch (RuntimeException e) {
            log.info("Courier call failed: {}", e.toString());
            return unavailable.apply(e.toString());
        }
    }

    private static ShipmentEvent systemEvent(Instant now, ShipmentStatus status, String key, String note) {
        return new ShipmentEvent(now, EventSource.SYSTEM, status, null, null, key, false, note);
    }

    private Shipment load(String shopId, String shipmentId) {
        if (shipmentId == null || !ObjectId.isValid(shipmentId)) {
            throw CourierErrors.shipmentNotFound();
        }
        return repository.findByIdAndShopId(shipmentId, shopId).orElseThrow(CourierErrors::shipmentNotFound);
    }

    private String userName(String shopId, String userId) {
        return currentUser.systemActor().map(a -> a.name())
                .orElseGet(() -> userService.findInShop(userId, shopId).map(User::getName).orElse("Unknown user"));
    }

    private static String text(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException e) {
            throw new FieldValidationException(field, "must be one of " + Arrays.toString(type.getEnumConstants()));
        }
    }
}

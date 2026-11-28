package com.sellerdesk.courier.service;

import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.common.security.SystemActor;
import com.sellerdesk.common.security.SystemActorScope;
import com.sellerdesk.common.transaction.TransactionRunner;
import com.sellerdesk.courier.dto.WebhookAck;
import com.sellerdesk.courier.model.CourierCode;
import com.sellerdesk.courier.model.EventSource;
import com.sellerdesk.courier.model.Shipment;
import com.sellerdesk.courier.model.ShipmentEvent;
import com.sellerdesk.courier.model.ShipmentStatus;
import com.sellerdesk.courier.provider.CourierUpdate;
import com.sellerdesk.courier.repository.ShipmentQueryRepository;
import com.sellerdesk.courier.repository.ShipmentRepository;
import com.sellerdesk.order.service.CourierOrderEffect;
import com.sellerdesk.order.service.OrderService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Applies one courier status report (webhook or poll) to a shipment and, through
 * {@link OrderService}, to its order (docs/modules/COURIER.md §5). The heart of the module.
 *
 * <p><b>One transaction</b> ({@link TransactionRunner}): the shipment update and the order change
 * (status, stock {@code DEDUCT}, customer counters) commit together or not at all. If anything
 * fails, the courier gets a 5xx, retries later, and the retry is safe because of the idempotency
 * rules below.
 *
 * <p><b>Idempotent</b>: every report has an {@code eventKey} (the courier's event id, else a hash of
 * consignment + status + time). A key that is already in the shipment's events changes nothing
 * ({@code DUPLICATE}). Two copies at the same moment both write the same shipment document, so one
 * gets a write conflict, is run again by the {@link TransactionRunner}, and then sees the key.
 *
 * <p><b>Runs as the courier</b>: {@link SystemActorScope} puts the shipment's shop into
 * {@code TenantContext} and a courier {@link SystemActor} into the security context, so order
 * history and stock movements show "Courier (Mock courier)". The caller passes {@code shopId}
 * from a trusted source only: our own shipment record (webhook routing) or the logged-in user.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShipmentEventApplier {

    private final ShipmentRepository repository;
    private final ShipmentQueryRepository queryRepository;
    private final OrderService orderService;
    private final TransactionRunner transactionRunner;
    private final CurrentUser currentUser;
    private final Clock clock;

    /**
     * @param consignmentId the courier's id from the report, if any (stored if we do not have it yet)
     */
    public WebhookAck.Result apply(String shopId, CourierCode provider, String shipmentId, String consignmentId,
                                   CourierUpdate update, EventSource source) {
        return SystemActorScope.runAs(shopId, SystemActor.courier(provider.displayName()),
                () -> transactionRunner.run(() -> applyInTransaction(shipmentId, consignmentId, update, source)));
    }

    private WebhookAck.Result applyInTransaction(String shipmentId, String consignmentId, CourierUpdate u,
                                                 EventSource source) {
        String shopId = currentUser.shopId();
        if (!ObjectId.isValid(shipmentId)) {
            throw CourierErrors.shipmentNotFound();
        }
        Shipment s = repository.findByIdAndShopId(shipmentId, shopId).orElseThrow(CourierErrors::shipmentNotFound);
        String consignment = s.getConsignmentId() != null ? s.getConsignmentId() : consignmentId;
        String key = eventKey(s.getProvider(), consignment, u);
        if (s.getEvents().stream().anyMatch(e -> key.equals(e.eventKey()))) {
            return WebhookAck.Result.DUPLICATE;
        }

        Instant now = Instant.now(clock);
        ShipmentStatus from = s.getStatus();
        ShipmentStatusMachine.Move move = ShipmentStatusMachine.check(from, u.status());
        Update update = new Update().set("updatedAt", now).set("lastEventAt", now);
        if (s.getConsignmentId() == null && consignmentId != null) {
            update.set("consignmentId", consignmentId);
        }

        if (move == ShipmentStatusMachine.Move.ILLEGAL) {
            String why = "Ignored: " + from + " → " + u.status() + " is not allowed";
            ShipmentQueryRepository.pushEvent(update, event(now, source, null, u, key, true, why));
            write(shopId, shipmentId, from, key, update);
            log.warn("Courier event ignored for shipment {}: {} (courier status '{}')", shipmentId, why, u.providerStatus());
            return WebhookAck.Result.IGNORED;
        }
        if (move == ShipmentStatusMachine.Move.SAME) {
            ShipmentQueryRepository.pushEvent(update, event(now, source, null, u, key, false, u.note()));
            write(shopId, shipmentId, from, key, update);
            return WebhookAck.Result.PROCESSED;
        }

        // A real status change.
        ShipmentStatus to = u.status();
        update.set("status", to).set("active", to.active());
        if (u.courierFee() != null) {
            update.set("courierFee", u.courierFee());
        }
        if (to == ShipmentStatus.DELIVERED && u.collectedAmount() != null) {
            update.set("collectedAmount", u.collectedAmount())
                    .set("codMismatch", u.collectedAmount() != s.getCodAmount());
        }
        ShipmentQueryRepository.pushEvent(update, event(now, source, to, u, key, false, u.note()));
        write(shopId, shipmentId, from, key, update);

        CourierOrderEffect effect = switch (to) {
            case PICKED_UP, IN_TRANSIT -> CourierOrderEffect.SHIP;
            case DELIVERED -> CourierOrderEffect.DELIVER;
            default -> null; // RETURNED: staff confirm the return with restock choices; CANCELLED / FAILED: order stays PACKED
        };
        if (effect != null) {
            CourierOrderEffect.Result result = orderService.applyCourierEvent(s.getOrderId(), effect,
                    s.getProvider().displayName(), s.getTrackingCode());
            if (result == CourierOrderEffect.Result.NOT_APPLICABLE) {
                log.warn("Courier reported {} for shipment {}, but its order {} could not follow", to, shipmentId,
                        s.getOrderNumber());
                Update note = ShipmentQueryRepository.pushEvent(new Update(), new ShipmentEvent(now, EventSource.SYSTEM,
                        null, null, null, "order:" + key, false,
                        "The order was not changed: its current status does not allow " + effect.name().toLowerCase()));
                write(shopId, shipmentId, to, null, note);
            }
        }
        return WebhookAck.Result.PROCESSED;
    }

    /** Inside the transaction we just read the document, so a miss means a parallel write: retried. */
    private void write(String shopId, String shipmentId, ShipmentStatus expected, String key, Update update) {
        if (!queryRepository.updateIf(shopId, shipmentId, expected, key, update)) {
            throw new OptimisticLockingFailureException("Shipment changed during a courier event");
        }
    }

    private static ShipmentEvent event(Instant now, EventSource source, ShipmentStatus status, CourierUpdate u,
                                       String key, boolean ignored, String note) {
        return new ShipmentEvent(now, source, status, u.providerStatus(), u.providerTime(), key, ignored, note);
    }

    /**
     * The courier's event id if it has one; otherwise a hash of what makes the report unique.
     * Package-private for tests.
     */
    static String eventKey(CourierCode provider, String consignmentId, CourierUpdate u) {
        if (u.providerEventId() != null) {
            return provider + ":" + u.providerEventId();
        }
        String raw = Objects.toString(consignmentId, "") + "|" + Objects.toString(u.providerStatus(), "") + "|"
                + Objects.toString(u.providerTime(), "");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return provider + ":h:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

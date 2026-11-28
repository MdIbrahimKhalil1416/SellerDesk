package com.sellerdesk.courier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerdesk.auth.service.UserService;
import com.sellerdesk.common.error.AppException;
import com.sellerdesk.common.error.ResourceNotFoundException;
import com.sellerdesk.common.model.Address;
import com.sellerdesk.common.model.DeliveryZone;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.courier.dto.SendShipmentRequest;
import com.sellerdesk.courier.model.CourierCode;
import com.sellerdesk.courier.model.Shipment;
import com.sellerdesk.courier.model.ShipmentStatus;
import com.sellerdesk.courier.provider.BookingResult;
import com.sellerdesk.courier.provider.CancelResult;
import com.sellerdesk.courier.provider.CourierProvider;
import com.sellerdesk.courier.provider.CourierProviderRegistry;
import com.sellerdesk.courier.repository.ShipmentQueryRepository;
import com.sellerdesk.courier.repository.ShipmentRepository;
import com.sellerdesk.order.model.OrderStatus;
import com.sellerdesk.order.service.OrderForCourier;
import com.sellerdesk.order.service.OrderService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;

/**
 * Business rules of {@link ShipmentService} with mocked collaborators: send rules, the three
 * booking outcomes, retries, cancel rules, and that every repository call uses the logged-in
 * user's shop. Real database behaviour is in {@code ShipmentIntegrationTest}.
 */
class ShipmentServiceTest {

    private static final String SHOP = new ObjectId().toHexString();
    private static final String USER = new ObjectId().toHexString();
    private static final String ORDER = new ObjectId().toHexString();
    private static final Instant NOW = Instant.parse("2026-11-28T10:00:00Z");
    private static final Address DHAKA = new Address("House 5", null, "Dhaka", DeliveryZone.INSIDE_DHAKA, null);

    private final ShipmentRepository repository = mock(ShipmentRepository.class);
    private final ShipmentQueryRepository queryRepository = mock(ShipmentQueryRepository.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final ShipmentEventApplier applier = mock(ShipmentEventApplier.class);
    private final CourierProviderRegistry registry = mock(CourierProviderRegistry.class);
    private final CourierProvider provider = mock(CourierProvider.class);
    private final OrderService orderService = mock(OrderService.class);
    private final UserService userService = mock(UserService.class);
    private final ShipmentService service = new ShipmentService(repository, queryRepository, new ShipmentMapper(currentUser),
            applier, registry, orderService, userService, currentUser, Clock.fixed(NOW, ZoneOffset.UTC));

    /** The shipment "in the database": updateIf applies $set to it when the expected status matches. */
    private final AtomicReference<Shipment> stored = new AtomicReference<>();

    @BeforeEach
    void loggedInAsShopA() {
        when(currentUser.shopId()).thenReturn(SHOP);
        when(currentUser.userId()).thenReturn(USER);
        when(userService.findInShop(USER, SHOP)).thenReturn(Optional.empty());
        when(registry.get(CourierCode.MOCK)).thenReturn(provider);
        when(provider.code()).thenReturn(CourierCode.MOCK);
        packed(OrderStatus.PACKED);
        when(repository.findByShopIdAndOrderIdAndActiveTrue(SHOP, ORDER)).thenReturn(Optional.empty());
        when(repository.insert(any(Shipment.class))).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(repository.findByIdAndShopId(anyString(), eq(SHOP))).thenAnswer(inv ->
                Optional.ofNullable(stored.get()).filter(s -> s.getId().equals(inv.getArgument(0))));
        when(queryRepository.updateIf(eq(SHOP), anyString(), any(), any(), any(Update.class))).thenAnswer(inv -> {
            Shipment s = stored.get();
            ShipmentStatus expected = inv.getArgument(2);
            if (s == null || (expected != null && s.getStatus() != expected)) {
                return false;
            }
            apply(s, inv.getArgument(4));
            return true;
        });
    }

    private void packed(OrderStatus status) {
        when(orderService.forCourier(ORDER)).thenReturn(new OrderForCourier(ORDER, "SD-7", status, 96_000, "Rahim",
                "01712345678", DHAKA, 2));
    }

    private static SendShipmentRequest mockCourier() {
        return new SendShipmentRequest(CourierCode.MOCK, " Call first ");
    }

    @Nested
    class Send {

        @Test
        void bookedShipmentIsCreatedWithTheUsersShopAndTheOrderStaysUntouched() {
            when(provider.createShipment(any())).thenReturn(new BookingResult.Created("MOCK-1", "MCK1"));

            ShipmentService.SendResult result = service.send(ORDER, mockCourier());

            assertThat(result.status()).isEqualTo(HttpStatus.CREATED);
            assertThat(result.shipment().status()).isEqualTo(ShipmentStatus.CREATED);
            assertThat(result.shipment().consignmentId()).isEqualTo("MOCK-1");
            ArgumentCaptor<Shipment> inserted = ArgumentCaptor.forClass(Shipment.class);
            verify(repository).insert(inserted.capture());
            Shipment s = inserted.getValue();
            assertThat(s.getShopId()).isEqualTo(SHOP);
            assertThat(s.getMerchantRef()).isEqualTo(s.getId());
            assertThat(s.getCodAmount()).isEqualTo(96_000);
            assertThat(s.getRecipient().phone()).isEqualTo("01712345678");
            assertThat(s.getNote()).isEqualTo("Call first");
            verify(queryRepository).updateIf(eq(SHOP), eq(s.getId()), eq(ShipmentStatus.BOOKING), isNull(), any());
            // The order is never changed by a booking: no courier effect on it.
            verify(orderService, never()).applyCourierEvent(any(), any(), any(), any());
        }

        @Test
        void onlyPackedOrders() {
            for (OrderStatus status : new OrderStatus[] {OrderStatus.PENDING, OrderStatus.CONFIRMED, OrderStatus.SHIPPED,
                    OrderStatus.CANCELLED}) {
                packed(status);
                assertThatThrownBy(() -> service.send(ORDER, mockCourier()))
                        .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getCode()).isEqualTo("ORDER_NOT_PACKED"));
            }
            verify(repository, never()).insert(any());
            verify(provider, never()).createShipment(any());
        }

        @Test
        void foreignOrderIsNotFound() {
            when(orderService.forCourier(ORDER)).thenThrow(new ResourceNotFoundException("NOT_FOUND", "Order not found"));
            assertThatThrownBy(() -> service.send(ORDER, mockCourier())).isInstanceOf(ResourceNotFoundException.class);
            verify(repository, never()).insert(any());
        }

        @Test
        void activeShipmentWithTheSameCourierIsReturnedWithoutASecondBooking() {
            Shipment active = shipment(ShipmentStatus.CREATED);
            when(repository.findByShopIdAndOrderIdAndActiveTrue(SHOP, ORDER)).thenReturn(Optional.of(active));

            ShipmentService.SendResult again = service.send(ORDER, mockCourier());
            assertThat(again.status()).isEqualTo(HttpStatus.OK);
            assertThat(again.shipment().id()).isEqualTo(active.getId());
            verify(provider, never()).createShipment(any());
        }

        @Test
        void parallelSendLosingTheUniqueIndexReturnsTheWinner() {
            Shipment winner = shipment(ShipmentStatus.BOOKING);
            when(repository.insert(any(Shipment.class))).thenThrow(new DuplicateKeyException("dup"));
            when(repository.findByShopIdAndOrderIdAndActiveTrue(SHOP, ORDER))
                    .thenReturn(Optional.empty(), Optional.of(winner));

            assertThat(service.send(ORDER, mockCourier()).shipment().id()).isEqualTo(winner.getId());
            verify(provider, never()).createShipment(any());
        }

        @Test
        void rejectedBookingIsFailedAnd409WithTheCouriersReason() {
            when(provider.createShipment(any())).thenReturn(new BookingResult.Rejected("Invalid phone",
                    Map.of("recipient.phone", "not accepted")));

            assertThatThrownBy(() -> service.send(ORDER, mockCourier()))
                    .isInstanceOfSatisfying(AppException.class, e -> {
                        assertThat(e.getCode()).isEqualTo("COURIER_REJECTED");
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.getMessage()).contains("Invalid phone");
                        assertThat(e.getFieldErrors()).extracting("field").containsExactly("recipient.phone");
                    });
            assertThat(stored.get().getStatus()).isEqualTo(ShipmentStatus.FAILED);
            assertThat(stored.get().getFailureReason()).isEqualTo("Invalid phone");
        }

        @Test
        void providerTimeoutOrErrorLeavesBookingWithARetryTimeAnd202() {
            when(provider.createShipment(any())).thenThrow(new IllegalStateException("connection reset"));

            ShipmentService.SendResult result = service.send(ORDER, mockCourier());

            assertThat(result.status()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(result.shipment().status()).isEqualTo(ShipmentStatus.BOOKING);
            assertThat(stored.get().getNextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
            assertThat(stored.get().getBookingAttempts()).isEqualTo(1);
        }

        @Test
        void orderCancelledDuringTheInsertUndoesTheBooking() {
            when(orderService.forCourier(ORDER)).thenReturn(
                    new OrderForCourier(ORDER, "SD-7", OrderStatus.PACKED, 96_000, "Rahim", "01712345678", DHAKA, 2),
                    new OrderForCourier(ORDER, "SD-7", OrderStatus.CANCELLED, 96_000, "Rahim", "01712345678", DHAKA, 2));

            assertThatThrownBy(() -> service.send(ORDER, mockCourier()))
                    .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getCode()).isEqualTo("ORDER_NOT_PACKED"));
            assertThat(stored.get().getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
            verify(provider, never()).createShipment(any());
        }
    }

    @Nested
    class Retries {

        @Test
        void giveUpAfterSixCallsAndAskForAManualCheck() {
            Shipment s = shipment(ShipmentStatus.BOOKING);
            s.setBookingAttempts(5);
            stored.set(s);
            when(provider.findByMerchantRef(s.getMerchantRef())).thenReturn(Optional.empty());
            when(provider.createShipment(any())).thenReturn(new BookingResult.Unavailable("timeout"));

            service.retryBooking(s.getId());

            assertThat(s.getStatus()).isEqualTo(ShipmentStatus.FAILED);
            assertThat(s.getFailureReason()).isEqualTo("COURIER_TIMEOUT");
            assertThat(s.getNeedsManualCheck()).isTrue();
        }

        @Test
        void aParcelCreatedByTheTimedOutCallIsFoundInsteadOfBookingTwice() {
            Shipment s = shipment(ShipmentStatus.BOOKING);
            s.setBookingAttempts(1);
            stored.set(s);
            when(provider.findByMerchantRef(s.getMerchantRef())).thenReturn(Optional.of(new BookingResult.Created("MOCK-9", "MCK9")));

            service.retryBooking(s.getId());

            assertThat(s.getStatus()).isEqualTo(ShipmentStatus.CREATED);
            assertThat(s.getConsignmentId()).isEqualTo("MOCK-9");
            verify(provider, never()).createShipment(any());
        }

        @Test
        void backoffGrowsWithEveryAttempt() {
            Shipment s = shipment(ShipmentStatus.BOOKING);
            s.setBookingAttempts(2);
            stored.set(s);
            when(provider.findByMerchantRef(any())).thenReturn(Optional.empty());
            when(provider.createShipment(any())).thenReturn(new BookingResult.Unavailable("timeout"));

            service.retryBooking(s.getId());

            assertThat(s.getBookingAttempts()).isEqualTo(3);
            assertThat(s.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(5 * 60));
        }
    }

    @Nested
    class Cancel {

        @Test
        void afterPickupIsNotCancellable() {
            stored.set(shipment(ShipmentStatus.PICKED_UP));
            assertThatThrownBy(() -> service.cancel(stored.get().getId(), "wait"))
                    .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getCode()).isEqualTo("SHIPMENT_NOT_CANCELLABLE"));
            verify(provider, never()).cancel(any());
        }

        @Test
        void courierDownIs503AndNothingChanges() {
            stored.set(shipment(ShipmentStatus.CREATED));
            when(provider.cancel("MOCK-1")).thenReturn(new CancelResult.Unavailable("timeout"));

            assertThatThrownBy(() -> service.cancel(stored.get().getId(), "wait"))
                    .isInstanceOfSatisfying(AppException.class, e -> {
                        assertThat(e.getCode()).isEqualTo("COURIER_UNAVAILABLE");
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    });
            assertThat(stored.get().getStatus()).isEqualTo(ShipmentStatus.CREATED);
        }

        @Test
        void createdIsCancelledAtTheCourierThenHere() {
            stored.set(shipment(ShipmentStatus.CREATED));
            when(provider.cancel("MOCK-1")).thenReturn(new CancelResult.Ok());

            assertThat(service.cancel(stored.get().getId(), "wait").status()).isEqualTo(ShipmentStatus.CANCELLED);
        }

        @Test
        void bookingIsCancelledLocallyWithoutACourierCall() {
            stored.set(shipment(ShipmentStatus.BOOKING));
            assertThat(service.cancel(stored.get().getId(), "wait").status()).isEqualTo(ShipmentStatus.CANCELLED);
            verify(provider, never()).cancel(any());
        }

        @Test
        void foreignOrMalformedIdIsNotFound() {
            assertThatThrownBy(() -> service.cancel(new ObjectId().toHexString(), "x")).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.cancel("not-an-id", "x")).isInstanceOf(ResourceNotFoundException.class);
            verify(repository).findByIdAndShopId(anyString(), eq(SHOP)); // always looked up in the user's own shop
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    private static Shipment shipment(ShipmentStatus status) {
        Shipment s = new Shipment();
        s.setId(new ObjectId().toHexString());
        s.setShopId(SHOP);
        s.setOrderId(ORDER);
        s.setOrderNumber("SD-7");
        s.setProvider(CourierCode.MOCK);
        s.setMerchantRef(s.getId());
        s.setStatus(status);
        s.setActive(status.active());
        s.setCodAmount(96_000);
        if (status != ShipmentStatus.BOOKING) {
            s.setConsignmentId("MOCK-1");
            s.setTrackingCode("MCK1");
        }
        return s;
    }

    /** Applies the {@code $set} part of an update to the in-memory shipment (enough for these tests). */
    private static void apply(Shipment s, Update update) {
        Object set = update.getUpdateObject().get("$set");
        if (!(set instanceof Document doc)) {
            return;
        }
        doc.forEach((key, value) -> {
            switch (key) {
                case "status" -> s.setStatus((ShipmentStatus) value);
                case "active" -> s.setActive((Boolean) value);
                case "consignmentId" -> s.setConsignmentId((String) value);
                case "trackingCode" -> s.setTrackingCode((String) value);
                case "failureReason" -> s.setFailureReason((String) value);
                case "needsManualCheck" -> s.setNeedsManualCheck((Boolean) value);
                case "bookingAttempts" -> s.setBookingAttempts((Integer) value);
                case "nextAttemptAt" -> s.setNextAttemptAt(value instanceof Date d ? d.toInstant() : (Instant) value);
                default -> { }
            }
        });
    }
}

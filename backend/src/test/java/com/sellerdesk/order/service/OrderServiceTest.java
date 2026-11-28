package com.sellerdesk.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sellerdesk.auth.service.UserService;
import com.sellerdesk.common.dto.AddressDto;
import com.sellerdesk.common.error.AppException;
import com.sellerdesk.common.error.FieldValidationException;
import com.sellerdesk.common.error.ResourceNotFoundException;
import com.sellerdesk.common.model.Address;
import com.sellerdesk.common.model.DeliveryZone;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.common.transaction.TransactionRunner;
import com.sellerdesk.customer.service.CustomerService;
import com.sellerdesk.customer.service.OrderCustomer;
import com.sellerdesk.inventory.service.InventoryErrors;
import com.sellerdesk.inventory.service.InventoryService;
import com.sellerdesk.inventory.service.StockLine;
import com.sellerdesk.order.dto.CreateOrderRequest;
import com.sellerdesk.order.dto.OrderCustomerRequest;
import com.sellerdesk.order.dto.OrderItemRequest;
import com.sellerdesk.order.model.Order;
import com.sellerdesk.order.model.OrderCustomerSnapshot;
import com.sellerdesk.order.model.OrderItem;
import com.sellerdesk.order.model.OrderSource;
import com.sellerdesk.order.model.OrderStatus;
import com.sellerdesk.order.repository.OrderQueryRepository;
import com.sellerdesk.order.repository.OrderRepository;
import com.sellerdesk.product.model.ProductStatus;
import com.sellerdesk.product.service.OrderableVariant;
import com.sellerdesk.product.service.ProductService;
import com.sellerdesk.shop.model.ShopSettings;
import com.sellerdesk.shop.service.ShopService;
import com.sellerdesk.shop.service.ShopSettingsLookup;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Business rules of {@link OrderService} with mocked collaborators: validation order, totals,
 * snapshots, the order number, and which stock / customer calls each transition makes. The real
 * database behaviour (transactions, concurrency) is in {@code OrderIntegrationTest}.
 */
class OrderServiceTest {

    private static final String SHOP = new ObjectId().toHexString();
    private static final String USER = new ObjectId().toHexString();
    private static final String ORDER = new ObjectId().toHexString();
    private static final String CUSTOMER = new ObjectId().toHexString();
    private static final String VARIANT = new ObjectId().toHexString();
    private static final String PRODUCT = new ObjectId().toHexString();
    private static final Instant NOW = Instant.parse("2026-11-28T10:00:00Z");
    private static final AddressDto DHAKA = new AddressDto("House 5", "Mirpur", "Dhaka", DeliveryZone.INSIDE_DHAKA, null);

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderQueryRepository queryRepository = mock(OrderQueryRepository.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final ProductService productService = mock(ProductService.class);
    private final InventoryService inventoryService = mock(InventoryService.class);
    private final CustomerService customerService = mock(CustomerService.class);
    private final ShopService shopService = mock(ShopService.class);
    private final ShopSettingsLookup settingsLookup = mock(ShopSettingsLookup.class);
    private final UserService userService = mock(UserService.class);
    private final TransactionRunner transactionRunner = mock(TransactionRunner.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final OrderService service = new OrderService(orderRepository, queryRepository, new OrderMapper(currentUser),
            productService, inventoryService, customerService, shopService, settingsLookup, userService, currentUser,
            transactionRunner, events, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void loggedInAsShopA() {
        when(currentUser.shopId()).thenReturn(SHOP);
        when(currentUser.userId()).thenReturn(USER);
        when(userService.findInShop(USER, SHOP)).thenReturn(Optional.empty());
        when(transactionRunner.run(any())).thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(0)).get());
        when(settingsLookup.forShop(SHOP)).thenReturn(new ShopSettings(6_000, 12_000, 5, "SD"));
        when(shopService.nextOrderNumber()).thenReturn(new ShopService.OrderNumber("SD", 7));
        when(orderRepository.findByShopIdAndRequestId(eq(SHOP), anyString())).thenReturn(Optional.empty());
        when(orderRepository.insert(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(ORDER);
            o.setVersion(0L);
            return o;
        });
        when(customerService.findOrCreateByPhone(any()))
                .thenReturn(new OrderCustomer(CUSTOMER, "Saved Name", "01712345678", null, null));
        when(productService.resolveForOrder(anyList())).thenReturn(Map.of(VARIANT, variant(45_000, true, ProductStatus.ACTIVE)));
    }

    private static OrderableVariant variant(long price, boolean active, ProductStatus status) {
        return new OrderableVariant(PRODUCT, "Cotton Tee", status, VARIANT, "TEE-M", "Red / M", price, active);
    }

    private static CreateOrderRequest request(List<OrderItemRequest> items, Long deliveryCharge, Long discount, Long advance) {
        return new CreateOrderRequest(UUID.randomUUID().toString(), null,
                new OrderCustomerRequest("Rahim", "+8801712345678", DHAKA), null, items, deliveryCharge, discount,
                advance, OrderSource.MESSENGER, "  Call first  ");
    }

    private static List<OrderItemRequest> twoOf(Long unitPrice) {
        return List.of(new OrderItemRequest(VARIANT, 2, unitPrice));
    }

    // ==== Create =========================================================================

    @Nested
    class Create {

        @Test
        void snapshotsItemsAndCustomerComputesTotalsAndCountsTheOrder() {
            var result = service.create(request(twoOf(null), null, 10_000L, 30_000L));

            ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
            verify(orderRepository).insert(saved.capture());
            Order o = saved.getValue();
            assertThat(o.getShopId()).isEqualTo(SHOP);
            assertThat(o.getOrderNumber()).isEqualTo(7);
            assertThat(o.displayNumber()).isEqualTo("SD-7");
            assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(o.getStatusHistory()).singleElement()
                    .satisfies(h -> assertThat(h.from()).isNull())
                    .satisfies(h -> assertThat(h.to()).isEqualTo(OrderStatus.PENDING));
            OrderItem item = o.getItems().get(0);
            assertThat(item.getProductName()).isEqualTo("Cotton Tee");
            assertThat(item.getSku()).isEqualTo("TEE-M");
            assertThat(item.getUnitPrice()).isEqualTo(45_000); // variant price, none sent
            assertThat(item.getLineTotal()).isEqualTo(90_000);
            assertThat(o.getSubtotal()).isEqualTo(90_000);
            assertThat(o.getDeliveryCharge()).isEqualTo(6_000); // inside Dhaka default
            assertThat(o.getTotal()).isEqualTo(86_000);          // 90000 − 10000 + 6000
            assertThat(o.getCodAmount()).isEqualTo(56_000);      // − 30000 advance
            // The order shows the name typed on this order, the customer's normalized phone.
            assertThat(o.getCustomer()).isEqualTo(new OrderCustomerSnapshot("Rahim", "01712345678", DHAKA.toModel()));
            assertThat(o.getNote()).isEqualTo("Call first");
            verify(customerService).recordOrderCreated(eq(CUSTOMER), any());
            verify(events).publishEvent(new OrderStatusChangedEvent(SHOP, ORDER, null, OrderStatus.PENDING));
            assertThat(result.created()).isTrue();
            // Nothing is reserved at create.
            verify(inventoryService, never()).reserve(anyList(), any());
        }

        @Test
        void explicitUnitPriceAndDeliveryChargeWin() {
            service.create(request(twoOf(40_000L), 0L, null, null));

            ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
            verify(orderRepository).insert(saved.capture());
            assertThat(saved.getValue().getItems().get(0).getUnitPrice()).isEqualTo(40_000);
            assertThat(saved.getValue().getDeliveryCharge()).isZero();
            assertThat(saved.getValue().getTotal()).isEqualTo(80_000);
        }

        @Test
        void bothOrNeitherCustomerFieldIs400BeforeAnythingElse() {
            CreateOrderRequest neither = new CreateOrderRequest(UUID.randomUUID().toString(), null, null, DHAKA,
                    twoOf(null), null, null, null, OrderSource.PHONE, null);
            CreateOrderRequest both = new CreateOrderRequest(UUID.randomUUID().toString(), CUSTOMER,
                    new OrderCustomerRequest("X", "01712345678", null), DHAKA, twoOf(null), null, null, null,
                    OrderSource.PHONE, null);

            assertThatThrownBy(() -> service.create(neither)).isInstanceOf(FieldValidationException.class);
            assertThatThrownBy(() -> service.create(both)).isInstanceOf(FieldValidationException.class);
            verifyNoInteractions(shopService, productService);
        }

        @Test
        void sameVariantTwiceIs400() {
            List<OrderItemRequest> items = List.of(new OrderItemRequest(VARIANT, 1, null), new OrderItemRequest(VARIANT, 2, null));
            assertThatThrownBy(() -> service.create(request(items, null, null, null)))
                    .isInstanceOf(FieldValidationException.class)
                    .satisfies(e -> assertThat(((AppException) e).getFieldErrors().get(0).field()).isEqualTo("items[1].variantId"));
        }

        @Test
        void archivedProductOrInactiveVariantIs409AndNoNumberIsTaken() {
            when(productService.resolveForOrder(anyList())).thenReturn(Map.of(VARIANT, variant(100, true, ProductStatus.ARCHIVED)));
            assertThatThrownBy(() -> service.create(request(twoOf(null), null, null, null)))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> assertThat(((AppException) e).getCode()).isEqualTo(OrderErrors.VARIANT_NOT_ORDERABLE));

            when(productService.resolveForOrder(anyList())).thenReturn(Map.of(VARIANT, variant(100, false, ProductStatus.ACTIVE)));
            assertThatThrownBy(() -> service.create(request(twoOf(null), null, null, null)))
                    .satisfies(e -> assertThat(((AppException) e).getFieldErrors().get(0).message()).contains("inactive"));

            verify(shopService, never()).nextOrderNumber();
            verify(orderRepository, never()).insert(any(Order.class));
        }

        @Test
        void unknownVariantIs404AndNoNumberIsTaken() {
            when(productService.resolveForOrder(anyList())).thenThrow(new ResourceNotFoundException("NOT_FOUND", "Variant not found"));
            assertThatThrownBy(() -> service.create(request(twoOf(null), null, null, null)))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(shopService, never()).nextOrderNumber();
        }

        @Test
        void discountAboveSubtotalIs400AndNoNumberIsTaken() {
            assertThatThrownBy(() -> service.create(request(twoOf(null), null, 90_001L, null)))
                    .isInstanceOf(FieldValidationException.class);
            verify(shopService, never()).nextOrderNumber();
        }

        @Test
        void noAddressAnywhereIs400() {
            CreateOrderRequest r = new CreateOrderRequest(UUID.randomUUID().toString(), null,
                    new OrderCustomerRequest("New", "01812345678", null), null, twoOf(null), null, null, null,
                    OrderSource.PHONE, null);
            when(customerService.lookup("01812345678")).thenThrow(new ResourceNotFoundException("NOT_FOUND", "x"));

            assertThatThrownBy(() -> service.create(r))
                    .isInstanceOf(FieldValidationException.class)
                    .satisfies(e -> assertThat(((AppException) e).getFieldErrors().get(0).field()).isEqualTo("deliveryAddress"));
            verify(shopService, never()).nextOrderNumber();
        }

        @Test
        void outsideDhakaAddressGetsTheOutsideCharge() {
            AddressDto ctg = new AddressDto("Road 1", null, "Chattogram", DeliveryZone.OUTSIDE_DHAKA, null);
            CreateOrderRequest r = new CreateOrderRequest(UUID.randomUUID().toString(), null,
                    new OrderCustomerRequest("Rahim", "01712345678", DHAKA), ctg, twoOf(null), null, null, null,
                    OrderSource.PHONE, null);
            service.create(r);

            ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
            verify(orderRepository).insert(saved.capture());
            assertThat(saved.getValue().getDeliveryCharge()).isEqualTo(12_000);
            assertThat(saved.getValue().getCustomer().address().district()).isEqualTo("Chattogram");
        }

        @Test
        void repeatedRequestIdReturnsTheFirstOrderOrIs409WhenDifferent() {
            CreateOrderRequest first = request(twoOf(null), null, null, null);
            Order stored = existingOrder(OrderStatus.PENDING);
            stored.setRequestId(first.requestId());
            when(orderRepository.findByShopIdAndRequestId(SHOP, first.requestId())).thenReturn(Optional.of(stored));

            var again = service.create(first);
            assertThat(again.created()).isFalse();
            assertThat(again.order().id()).isEqualTo(ORDER);

            CreateOrderRequest other = new CreateOrderRequest(first.requestId(), null, first.customer(), null,
                    List.of(new OrderItemRequest(VARIANT, 5, null)), null, null, null, OrderSource.PHONE, null);
            assertThatThrownBy(() -> service.create(other))
                    .satisfies(e -> assertThat(((AppException) e).getCode()).isEqualTo(OrderErrors.REQUEST_ID_REUSED));
            verify(shopService, never()).nextOrderNumber();
            verify(orderRepository, never()).insert(any(Order.class));
        }
    }

    // ==== Status changes =================================================================

    @Nested
    class Transitions {

        private Order order;

        @BeforeEach
        void anOrder() {
            when(queryRepository.updateIf(eq(SHOP), eq(ORDER), any(), isNull(), any())).thenReturn(true);
        }

        private void orderIs(OrderStatus status) {
            order = existingOrder(status);
            when(orderRepository.findByIdAndShopId(ORDER, SHOP)).thenReturn(Optional.of(order));
        }

        private List<StockLine> lines() {
            return List.of(new StockLine(VARIANT, 2));
        }

        @Test
        void confirmReservesEveryItemAndTouchesNoCounter() {
            orderIs(OrderStatus.PENDING);
            service.confirm(ORDER, "ok");

            verify(queryRepository).updateIf(eq(SHOP), eq(ORDER), eq(OrderStatus.PENDING), isNull(), any());
            verify(inventoryService).reserve(eq(lines()), any());
            verifyNoInteractions(customerService);
        }

        @Test
        void shipDeductsAndDeliverCountsTheTotal() {
            orderIs(OrderStatus.PACKED);
            service.ship(ORDER, null);
            verify(inventoryService).deduct(eq(lines()), any());

            orderIs(OrderStatus.SHIPPED);
            service.deliver(ORDER, null);
            verify(customerService).recordDelivered(CUSTOMER, 96_000);
        }

        @Test
        void cancelFromPendingReleasesNothingFromConfirmedReleases() {
            orderIs(OrderStatus.PENDING);
            service.cancel(ORDER, "changed mind");
            verify(inventoryService, never()).release(anyList(), any());
            verify(customerService).recordCancelled(CUSTOMER);

            orderIs(OrderStatus.CONFIRMED);
            service.cancel(ORDER, "changed mind");
            verify(inventoryService).release(eq(lines()), any());
        }

        @Test
        void illegalTransitionIs409WithoutAnyWrite() {
            orderIs(OrderStatus.SHIPPED);
            assertThatThrownBy(() -> service.cancel(ORDER, "too late"))
                    .satisfies(e -> assertThat(((AppException) e).getCode()).isEqualTo(OrderErrors.INVALID_STATUS_TRANSITION));
            verify(queryRepository, never()).updateIf(anyString(), anyString(), any(), any(), any());
            verifyNoInteractions(inventoryService, customerService);
        }

        @Test
        void alreadyInTheTargetStatusIsANoOp() {
            orderIs(OrderStatus.CONFIRMED);
            service.confirm(ORDER, null);
            verify(queryRepository, never()).updateIf(anyString(), anyString(), any(), any(), any());
            verifyNoInteractions(inventoryService);
        }

        @Test
        void unknownOrMalformedOrderIs404() {
            when(orderRepository.findByIdAndShopId(ORDER, SHOP)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.confirm(ORDER, null)).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.confirm("not-an-id", null)).isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void insufficientStockOnConfirmListsEveryShortItem() {
            orderIs(OrderStatus.PENDING);
            when(inventoryService.reserve(anyList(), any()))
                    .thenThrow(InventoryErrors.insufficientStock("TEE-M", 2, 1));
            when(inventoryService.availableFor(anyList())).thenReturn(Map.of(VARIANT, 1));

            assertThatThrownBy(() -> service.confirm(ORDER, null))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> {
                        AppException app = (AppException) e;
                        assertThat(app.getCode()).isEqualTo(OrderErrors.INSUFFICIENT_STOCK);
                        assertThat(app.getFieldErrors()).singleElement()
                                .satisfies(f -> assertThat(f.field()).isEqualTo("items[0].quantity"))
                                .satisfies(f -> assertThat(f.message()).isEqualTo("TEE-M: requested 2, available 1"));
                    });
        }

        @Test
        void returnRestocksOnlyTheChosenItems() {
            orderIs(OrderStatus.SHIPPED);
            service.returnOrder(ORDER, new com.sellerdesk.order.dto.ReturnRequest("damaged",
                    List.of(new com.sellerdesk.order.dto.ReturnRequest.Item(VARIANT, false))));
            verify(inventoryService, never()).restockReturn(anyList(), any());
            verify(customerService).recordReturned(CUSTOMER);
        }

        @Test
        void returnWithAVariantNotInTheOrderIs400() {
            orderIs(OrderStatus.SHIPPED);
            String other = new ObjectId().toHexString();
            assertThatThrownBy(() -> service.returnOrder(ORDER, new com.sellerdesk.order.dto.ReturnRequest("x",
                    List.of(new com.sellerdesk.order.dto.ReturnRequest.Item(other, true)))))
                    .isInstanceOf(FieldValidationException.class);
            verify(queryRepository, never()).updateIf(anyString(), anyString(), any(), any(), any());
        }
    }

    // ==== Bulk ===========================================================================

    @Nested
    class Bulk {

        private com.sellerdesk.order.dto.BulkStatusRequest request(OrderAction action, String reason, String... ids) {
            return new com.sellerdesk.order.dto.BulkStatusRequest(action, List.of(ids), null, reason, null);
        }

        @Test
        void missingPermissionOfTheActionIs403BeforeAnyOrderIsTouched() {
            when(currentUser.hasPermission(com.sellerdesk.common.security.Permission.ORDER_CONFIRM)).thenReturn(false);
            assertThatThrownBy(() -> service.bulk(request(OrderAction.CONFIRM, null, ORDER)))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            verifyNoInteractions(orderRepository, inventoryService);
        }

        @Test
        void returnInBulkCancelWithoutReasonAndDuplicatesAre400() {
            when(currentUser.hasPermission(any())).thenReturn(true);
            assertThatThrownBy(() -> service.bulk(request(OrderAction.RETURN, "x", ORDER)))
                    .satisfies(e -> assertThat(((AppException) e).getFieldErrors().get(0).field()).isEqualTo("action"));
            assertThatThrownBy(() -> service.bulk(request(OrderAction.CANCEL, " ", ORDER)))
                    .satisfies(e -> assertThat(((AppException) e).getFieldErrors().get(0).field()).isEqualTo("reason"));
            assertThatThrownBy(() -> service.bulk(request(OrderAction.PACK, null, ORDER, ORDER)))
                    .satisfies(e -> assertThat(((AppException) e).getFieldErrors().get(0).field()).isEqualTo("orderIds"));
            verifyNoInteractions(orderRepository);
        }

        @Test
        void oneFailingOrderDoesNotStopTheOthers() {
            when(currentUser.hasPermission(any())).thenReturn(true);
            String missing = new ObjectId().toHexString();
            Order pending = existingOrder(OrderStatus.PENDING);
            when(orderRepository.findByIdAndShopId(ORDER, SHOP)).thenReturn(Optional.of(pending));
            when(orderRepository.findByIdAndShopId(missing, SHOP)).thenReturn(Optional.empty());
            when(queryRepository.updateIf(eq(SHOP), eq(ORDER), any(), isNull(), any())).thenReturn(true);

            var result = service.bulk(request(OrderAction.CANCEL, "spam", missing, ORDER));

            assertThat(result.succeeded()).isOne();
            assertThat(result.failed()).isOne();
            assertThat(result.results().get(0).errorCode()).isEqualTo(OrderErrors.NOT_FOUND);
            assertThat(result.results().get(1).ok()).isTrue();
            assertThat(result.results().get(1).orderNumber()).isEqualTo("SD-7");
            verify(customerService).recordCancelled(CUSTOMER);
        }
    }

    @Test
    void itemSummaryDescribesAddedChangedAndRemovedLines() {
        OrderItem tee = item("TEE-M", "v1", 1, 100);
        OrderItem cap = item("CAP-1", "v2", 1, 100);
        String summary = OrderService.itemSummary(List.of(tee, cap),
                List.of(item("TEE-M", "v1", 2, 100), item("SOCK-1", "v3", 3, 50)));
        assertThat(summary).isEqualTo("TEE-M qty 1 → 2, +SOCK-1 ×3, −CAP-1");
        assertThat(OrderService.itemSummary(List.of(tee), List.of(item("TEE-M", "v1", 1, 100)))).isNull();
    }

    private static OrderItem item(String sku, String variantId, int quantity, long price) {
        OrderItem i = new OrderItem();
        i.setSku(sku);
        i.setVariantId(variantId);
        i.setQuantity(quantity);
        i.setUnitPrice(price);
        i.setLineTotal(price * quantity);
        return i;
    }

    private static Order existingOrder(OrderStatus status) {
        Order o = new Order();
        o.setId(ORDER);
        o.setShopId(SHOP);
        o.setOrderNumber(7);
        o.setOrderNumberPrefix("SD");
        o.setCustomerId(CUSTOMER);
        o.setCustomer(new OrderCustomerSnapshot("Rahim", "01712345678", new Address("House 5", null, "Dhaka",
                DeliveryZone.INSIDE_DHAKA, null)));
        OrderItem item = new OrderItem();
        item.setProductId(PRODUCT);
        item.setVariantId(VARIANT);
        item.setSku("TEE-M");
        item.setUnitPrice(45_000);
        item.setQuantity(2);
        item.setLineTotal(90_000);
        o.setItems(new ArrayList<>(List.of(item)));
        o.setSubtotal(90_000);
        o.setDeliveryCharge(6_000);
        o.setTotal(96_000);
        o.setCodAmount(96_000);
        o.setStatus(status);
        o.setVersion(0L);
        return o;
    }
}

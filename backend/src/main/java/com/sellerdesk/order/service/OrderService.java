package com.sellerdesk.order.service;

import com.sellerdesk.auth.model.User;
import com.sellerdesk.auth.service.UserService;
import com.sellerdesk.common.dto.PageResponse;
import com.sellerdesk.common.error.ApiError;
import com.sellerdesk.common.error.AppException;
import com.sellerdesk.common.error.FieldValidationException;
import com.sellerdesk.common.error.ResourceNotFoundException;
import com.sellerdesk.common.model.Address;
import com.sellerdesk.common.phone.BdPhone;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.transaction.TransactionRunner;
import com.sellerdesk.customer.dto.CustomerResponse;
import com.sellerdesk.customer.service.CustomerService;
import com.sellerdesk.customer.service.NewOrderCustomer;
import com.sellerdesk.customer.service.OrderCustomer;
import com.sellerdesk.inventory.service.InventoryService;
import com.sellerdesk.inventory.service.OrderRef;
import com.sellerdesk.inventory.service.StockLine;
import com.sellerdesk.order.dto.BulkStatusRequest;
import com.sellerdesk.order.dto.BulkStatusResponse;
import com.sellerdesk.order.dto.CreateOrderRequest;
import com.sellerdesk.order.dto.OrderItemRequest;
import com.sellerdesk.order.dto.OrderResponse;
import com.sellerdesk.order.dto.OrderSummary;
import com.sellerdesk.order.dto.ReturnRequest;
import com.sellerdesk.order.dto.ShipRequest;
import com.sellerdesk.order.dto.TimelineEntry;
import com.sellerdesk.order.dto.UpdateOrderRequest;
import com.sellerdesk.order.model.Order;
import com.sellerdesk.order.model.OrderCustomerSnapshot;
import com.sellerdesk.order.model.OrderEdit;
import com.sellerdesk.order.model.OrderItem;
import com.sellerdesk.order.model.OrderSource;
import com.sellerdesk.order.model.OrderStatus;
import com.sellerdesk.order.model.Shipping;
import com.sellerdesk.order.model.StatusChange;
import com.sellerdesk.order.repository.OrderQueryRepository;
import com.sellerdesk.order.repository.OrderRepository;
import com.sellerdesk.product.service.OrderableVariant;
import com.sellerdesk.product.service.ProductService;
import com.sellerdesk.shop.model.ShopSettings;
import com.sellerdesk.shop.service.ShopService;
import com.sellerdesk.shop.service.ShopSettingsLookup;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orders (docs/modules/ORDER.md).
 *
 * <p><b>Rules every method follows</b>
 * <ol>
 *   <li>The shop is always {@link CurrentUser#shopId()} (the JWT). Another shop's order, customer
 *       or variant is "not found" (404).</li>
 *   <li>Totals are always computed here ({@link OrderRules#totals}); the client never sends them.</li>
 *   <li>Stock changes only through {@link InventoryService}, customer counters only through
 *       {@link CustomerService}, both <b>inside the same transaction</b> as the order change
 *       ({@link TransactionRunner}, which retries on write conflicts). Either everything commits
 *       or nothing does.</li>
 *   <li>After create, the order is never saved as a whole: every change is a targeted update that
 *       states the status (and for edits the version) it expects.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final int MAX_SUMMARY = 300;

    private final OrderRepository orderRepository;
    private final OrderQueryRepository queryRepository;
    private final OrderMapper mapper;
    private final ProductService productService;
    private final InventoryService inventoryService;
    private final CustomerService customerService;
    private final ShopService shopService;
    private final ShopSettingsLookup shopSettingsLookup;
    private final UserService userService;
    private final CurrentUser currentUser;
    private final TransactionRunner transactionRunner;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    /** Implemented by the courier module; empty if it is not there. */
    private final List<ShipmentGuard> shipmentGuards;

    /** @param created false when the same {@code requestId} was sent before (the first order is returned) */
    public record CreateResult(OrderResponse order, boolean created) {
    }

    // ==== Read ===========================================================================

    public OrderResponse get(String orderId) {
        return respond(load(currentUser.shopId(), orderId));
    }

    /**
     * The shop's orders, newest first by default (docs/modules/ORDER.md §8). Every filter is
     * optional; they combine with AND.
     *
     * @param statuses any of these ({@code ?status=PENDING&status=CONFIRMED})
     * @param from     Dhaka day {@code YYYY-MM-DD}, inclusive
     * @param to       Dhaka day, inclusive
     * @param q        order number ({@code SD-1042}, {@code 1042}) or customer phone (any format)
     * @param sort     {@code createdAt,desc} (default) or {@code createdAt,asc}
     */
    public PageResponse<OrderSummary> list(List<String> statuses, String source, String from, String to, String q,
                                           String sort, int page, int size) {
        String shopId = currentUser.shopId();
        Instant fromInstant = OrderRules.startOfDay(from, "from");
        Instant toExclusive = OrderRules.endOfDay(to, "to");
        if (fromInstant != null && toExclusive != null && !fromInstant.isBefore(toExclusive)) {
            throw new FieldValidationException("from", "must not be after to");
        }
        OrderRules.Search search = OrderRules.parseSearch(q);
        OrderQueryRepository.ListFilter filter = new OrderQueryRepository.ListFilter(
                parseStatuses(statuses), parseEnum(OrderSource.class, source, "source"), fromInstant, toExclusive,
                search == null ? null : search.orderNumber(), search == null ? null : search.phone(),
                parseAscending(sort), page, size);
        OrderQueryRepository.ListResult result = queryRepository.list(shopId, filter);
        return PageResponse.of(result.content().stream().map(OrderMapper::toSummary).toList(), page, size,
                result.totalElements());
    }

    /** Status changes and edits of one order, oldest first (docs/modules/ORDER.md §8). */
    public List<TimelineEntry> timeline(String orderId) {
        Order order = load(currentUser.shopId(), orderId);
        List<TimelineEntry> entries = new ArrayList<>();
        for (StatusChange h : order.getStatusHistory()) {
            entries.add(new TimelineEntry(TimelineEntry.Type.STATUS, h.at(), new OrderResponse.Actor(h.by(), h.byName()),
                    h.from(), h.to(), h.note(), null, null));
        }
        for (OrderEdit e : order.getEdits()) {
            entries.add(new TimelineEntry(TimelineEntry.Type.EDIT, e.at(), new OrderResponse.Actor(e.by(), e.byName()),
                    null, null, null, e.fields(), e.summary()));
        }
        // Stable sort: at equal times, status changes stay before edits and each list keeps its order.
        entries.sort(Comparator.comparing(TimelineEntry::at));
        return entries;
    }

    // ==== Create =========================================================================

    /**
     * Creates a {@code PENDING} order (docs/modules/ORDER.md §6–§7). Stock is <b>not</b> reserved
     * here; that happens on confirm. Steps:
     * <ol>
     *   <li>Validate everything that can be checked without writing: customer choice, unique
     *       variants, variants exist and are orderable, delivery address, totals.</li>
     *   <li>Take the order number ({@code $inc} on the shop), outside the transaction.</li>
     *   <li>In one transaction: find-or-create the customer, insert the order, count it on the customer.</li>
     * </ol>
     * A repeated {@code requestId} returns the first order instead of creating a second one.
     */
    public CreateResult create(CreateOrderRequest request) {
        String shopId = currentUser.shopId();
        boolean byId = OrderRules.text(request.customerId()) != null;
        if (byId == (request.customer() != null)) {
            throw new FieldValidationException("customerId", "send either customerId or customer, not both or neither");
        }
        requireUniqueVariants(request.items());

        Optional<Order> earlier = orderRepository.findByShopIdAndRequestId(shopId, request.requestId());
        if (earlier.isPresent()) {
            return replay(earlier.get(), request);
        }

        Map<String, OrderableVariant> variants = productService.resolveForOrder(variantIds(request.items()));
        requireOrderable(request.items(), variants);
        List<OrderItem> items = buildItems(request.items(), variants, Map.of());

        OrderCustomer knownCustomer = byId ? customerService.getForOrder(request.customerId()) : null;
        Address address = deliveryAddress(request, knownCustomer);
        ShopSettings settings = shopSettingsLookup.forShop(shopId);
        OrderRules.Totals totals = OrderRules.totals(lineTotals(items), request.discount(),
                OrderRules.deliveryCharge(request.deliveryCharge(), address.zone(), settings), request.advancePaid());

        // Outside the transaction on purpose (docs/modules/ORDER.md §3). Retries reuse this number.
        ShopService.OrderNumber number = shopService.nextOrderNumber();
        String userId = currentUser.userId();
        String userName = userName(shopId, userId);
        try {
            Order saved = transactionRunner.run(() -> {
                OrderCustomer customer = byId
                        ? customerService.getForOrder(request.customerId())
                        : customerService.findOrCreateByPhone(new NewOrderCustomer(request.customer().name(),
                                request.customer().phone(), request.customer().address()));
                String name = byId ? customer.name()
                        : Objects.requireNonNullElse(OrderRules.text(request.customer().name()), customer.name());
                Instant now = Instant.now(clock);

                Order order = new Order();
                order.setShopId(shopId);
                order.setOrderNumber(number.number());
                order.setOrderNumberPrefix(number.prefix());
                order.setRequestId(request.requestId());
                order.setCustomerId(customer.customerId());
                order.setCustomer(new OrderCustomerSnapshot(name, customer.phone(), address));
                order.setItems(items);
                applyTotals(order, totals);
                order.setStatus(OrderStatus.PENDING);
                order.setStatusHistory(new ArrayList<>(List.of(
                        new StatusChange(null, OrderStatus.PENDING, now, userId, userName, null))));
                order.setSource(request.source());
                order.setNote(OrderRules.text(request.note()));
                order.setCreatedBy(userId);
                order.setCreatedByName(userName);
                Order inserted = orderRepository.insert(order);

                Instant createdAt = inserted.getCreatedAt() == null ? now : inserted.getCreatedAt();
                customerService.recordOrderCreated(customer.customerId(), createdAt);
                events.publishEvent(new OrderStatusChangedEvent(shopId, inserted.getId(), null, OrderStatus.PENDING));
                return inserted;
            });
            return new CreateResult(respond(saved), true);
        } catch (DuplicateKeyException e) {
            // A parallel request with the same requestId committed first; the unique index stopped us.
            Order winner = orderRepository.findByShopIdAndRequestId(shopId, request.requestId()).orElseThrow(() -> e);
            return replay(winner, request);
        }
    }

    // ==== Edit (PENDING only) ============================================================

    /**
     * Full replace of the editable fields while {@code PENDING} (docs/modules/ORDER.md §8). Items
     * already in the order keep their stored price unless a new one is sent; new items take the
     * variant's current price and must be orderable. Nothing is reserved yet, so stock is not touched.
     */
    public OrderResponse update(String orderId, UpdateOrderRequest request) {
        String shopId = currentUser.shopId();
        Order order = load(shopId, orderId);
        if (order.getStatus() != OrderStatus.PENDING) {
            throw OrderErrors.notEditable(order.getStatus());
        }
        requireUniqueVariants(request.items());

        Map<String, OrderItem> existing = new HashMap<>();
        order.getItems().forEach(i -> existing.put(i.getVariantId(), i));
        List<String> newIds = variantIds(request.items()).stream().filter(id -> !existing.containsKey(id)).toList();
        Map<String, OrderableVariant> variants = newIds.isEmpty() ? Map.of() : productService.resolveForOrder(newIds);
        requireOrderable(request.items(), variants);
        List<OrderItem> items = buildItems(request.items(), variants, existing);

        Address address = request.deliveryAddress().toModel();
        ShopSettings settings = shopSettingsLookup.forShop(shopId);
        OrderRules.Totals totals = OrderRules.totals(lineTotals(items), request.discount(),
                OrderRules.deliveryCharge(request.deliveryCharge(), address.zone(), settings), request.advancePaid());
        String name = Objects.requireNonNullElse(OrderRules.text(request.customerName()), order.getCustomer().name());
        OrderCustomerSnapshot customer = new OrderCustomerSnapshot(name, order.getCustomer().phone(), address);
        String note = OrderRules.text(request.note());

        List<String> fields = changedFields(order, customer, items, totals, request, note);
        if (fields.isEmpty() && Objects.equals(order.getVersion(), request.version())) {
            return respond(order);
        }

        Instant now = Instant.now(clock);
        String userId = currentUser.userId();
        OrderEdit edit = new OrderEdit(now, userId, userName(shopId, userId), fields,
                truncate(itemSummary(order.getItems(), items)));
        Update update = new Update()
                .set("customer", customer)
                .set("items", items)
                .set("subtotal", totals.subtotal())
                .set("discount", totals.discount())
                .set("deliveryCharge", totals.deliveryCharge())
                .set("total", totals.total())
                .set("advancePaid", totals.advancePaid())
                .set("codAmount", totals.codAmount())
                .set("source", request.source())
                .set("updatedAt", now);
        if (note == null) {
            update.unset("note");
        } else {
            update.set("note", note);
        }
        update.push("edits").slice(-Order.MAX_EDITS).each(edit);

        if (!queryRepository.updateIf(shopId, orderId, OrderStatus.PENDING, request.version(), update)) {
            Order current = load(shopId, orderId);
            throw current.getStatus() != OrderStatus.PENDING ? OrderErrors.notEditable(current.getStatus())
                    : OrderErrors.staleVersion();
        }
        return respond(load(shopId, orderId));
    }

    // ==== Bulk status change =============================================================

    /**
     * The same action on many orders (docs/modules/ORDER.md §8). Each order goes through exactly
     * the same method as its single endpoint, in <b>its own transaction</b>, one after the other:
     * an order that fails (wrong status, not enough stock, not found) is reported and the others
     * still run. An order of another shop is reported as {@code NOT_FOUND}, like an unknown id.
     *
     * <p>One endpoint serves several permissions, so the permission of the chosen action is
     * checked here: missing → 403 for the whole request, nothing is changed.
     */
    public BulkStatusResponse bulk(BulkStatusRequest request) {
        OrderAction action = request.action();
        if (!currentUser.hasPermission(action.permission())) {
            throw new AccessDeniedException("Missing permission " + action.permission());
        }
        if (action == OrderAction.RETURN) {
            throw new FieldValidationException("action", "RETURN is not possible in bulk: each item needs a restock choice");
        }
        String reason = OrderRules.text(request.reason());
        if (action == OrderAction.CANCEL && reason == null) {
            throw new FieldValidationException("reason", "is required to cancel");
        }
        if (new HashSet<>(request.orderIds()).size() != request.orderIds().size()) {
            throw new FieldValidationException("orderIds", "must not contain the same order twice");
        }

        List<BulkStatusResponse.Result> results = new ArrayList<>();
        for (String orderId : request.orderIds()) {
            results.add(bulkOne(action, orderId, request, reason));
        }
        int succeeded = (int) results.stream().filter(BulkStatusResponse.Result::ok).count();
        return new BulkStatusResponse(action, succeeded, results.size() - succeeded, results);
    }

    private BulkStatusResponse.Result bulkOne(OrderAction action, String orderId, BulkStatusRequest request,
                                              String reason) {
        try {
            OrderResponse done = switch (action) {
                case CONFIRM -> confirm(orderId, request.note());
                case PACK -> pack(orderId, request.note());
                case SHIP -> ship(orderId, new ShipRequest(request.courierName(), null, request.note()));
                case DELIVER -> deliver(orderId, request.note());
                case CANCEL -> cancel(orderId, reason);
                case RETURN -> throw new IllegalStateException("RETURN is refused before the loop");
            };
            return new BulkStatusResponse.Result(orderId, done.orderNumber(), true, done.status(), null, null);
        } catch (AppException e) {
            return new BulkStatusResponse.Result(orderId, null, false, null, e.getCode(), e.getMessage());
        } catch (OptimisticLockingFailureException e) {
            return new BulkStatusResponse.Result(orderId, null, false, null, "CONCURRENT_MODIFICATION",
                    "The order was changed by someone else; try again");
        }
    }

    // ==== Status changes =================================================================

    public OrderResponse confirm(String orderId, String note) {
        return transition(orderId, OrderAction.CONFIRM, Extras.note(note));
    }

    public OrderResponse pack(String orderId, String note) {
        return transition(orderId, OrderAction.PACK, Extras.note(note));
    }

    public OrderResponse ship(String orderId, ShipRequest request) {
        ShipRequest r = request == null ? new ShipRequest(null, null, null) : request;
        Shipping shipping = OrderRules.text(r.courierName()) == null && OrderRules.text(r.trackingId()) == null ? null
                : new Shipping(OrderRules.text(r.courierName()), OrderRules.text(r.trackingId()));
        return transition(orderId, OrderAction.SHIP, new Extras(OrderRules.text(r.note()), shipping, null, Map.of()));
    }

    public OrderResponse deliver(String orderId, String note) {
        return transition(orderId, OrderAction.DELIVER, Extras.note(note));
    }

    public OrderResponse cancel(String orderId, String reason) {
        return transition(orderId, OrderAction.CANCEL, new Extras(OrderRules.text(reason), null, OrderRules.text(reason), Map.of()));
    }

    /** Items not listed are restocked; {@code restock = false} items (damaged) get no stock movement. */
    public OrderResponse returnOrder(String orderId, ReturnRequest request) {
        Map<String, Boolean> restock = new LinkedHashMap<>();
        List<ReturnRequest.Item> listed = request.items() == null ? List.of() : request.items();
        for (ReturnRequest.Item item : listed) {
            restock.put(item.variantId(), item.restock());
        }
        String reason = OrderRules.text(request.reason());
        return transition(orderId, OrderAction.RETURN, new Extras(reason, null, reason, restock));
    }

    /**
     * What a status change carries besides the status.
     *
     * @param historyNote note of the history entry (the reason for cancel / return)
     * @param reason      cancel or return reason
     * @param restock     return only: variant id → put back on the shelf? (missing = yes)
     */
    private record Extras(String historyNote, Shipping shipping, String reason, Map<String, Boolean> restock) {
        static Extras note(String note) {
            return new Extras(OrderRules.text(note), null, null, Map.of());
        }
    }

    /**
     * One status change in one transaction (docs/modules/ORDER.md §6):
     * <ol>
     *   <li>load the order (404); already in the target status → return it unchanged (a retried
     *       click or a transaction retry after an unknown commit result does nothing twice);</li>
     *   <li>not allowed from the current status → 409 INVALID_STATUS_TRANSITION;</li>
     *   <li>update the order <b>only if it still has that status</b>, with the history entry;</li>
     *   <li>stock through {@link InventoryService}, counters through {@link CustomerService}.</li>
     * </ol>
     * If step 4 fails (not enough stock), everything rolls back: status, history, and the
     * reservations of items already done.
     */
    private OrderResponse transition(String orderId, OrderAction action, Extras extras) {
        String shopId = currentUser.shopId();
        String userId = currentUser.userId();
        String userName = userName(shopId, userId);
        try {
            transactionRunner.run(() -> transitionInTransaction(shopId, userId, userName, orderId, action, extras, true));
        } catch (AppException e) {
            if (action == OrderAction.CONFIRM && OrderErrors.INSUFFICIENT_STOCK.equals(e.getCode())) {
                throw shortageReport(load(shopId, orderId), e);
            }
            throw e;
        }
        return respond(load(shopId, orderId));
    }

    /**
     * The body of one status change; runs inside the caller's transaction (a user's action via
     * {@link #transition}, or a courier event via {@link #applyCourierEvent}).
     *
     * @param manual true for a user's action: then shipping and cancelling are refused while the
     *               order is booked with a courier ({@link ShipmentGuard}, docs/modules/COURIER.md §6)
     */
    private Order transitionInTransaction(String shopId, String userId, String userName, String orderId,
                                          OrderAction action, Extras extras, boolean manual) {
        Order order = load(shopId, orderId);
        if (order.getStatus() == action.to()) {
            return order;
        }
        if (!action.allowedFrom(order.getStatus())) {
            throw OrderErrors.invalidTransition(order.getStatus(), action);
        }
        if (manual && (action == OrderAction.SHIP || action == OrderAction.CANCEL)
                && shipmentGuards.stream().anyMatch(g -> g.hasActiveShipment(shopId, orderId))) {
            throw OrderErrors.shipmentActive(action);
        }
        OrderStatus from = order.getStatus();
        Instant now = Instant.now(clock);
        Update update = new Update()
                .set("status", action.to())
                .push("statusHistory", new StatusChange(from, action.to(), now, userId, userName, extras.historyNote()))
                .set("updatedAt", now);
        if (extras.shipping() != null) {
            update.set("shipping", extras.shipping());
        }
        if (action == OrderAction.CANCEL) {
            update.set("cancelReason", extras.reason());
        }
        List<StockLine> restockLines = List.of();
        if (action == OrderAction.RETURN) {
            update.set("returnReason", extras.reason());
            restockLines = returnLines(order, extras.restock(), update);
        }
        if (!queryRepository.updateIf(shopId, orderId, from, null, update)) {
            // Cannot happen inside the transaction (we just read it); a parallel change
            // would have been a write conflict, retried by the TransactionRunner.
            throw OrderErrors.staleVersion();
        }
        applySideEffects(order, action, from, restockLines);
        events.publishEvent(new OrderStatusChangedEvent(shopId, orderId, from, action.to()));
        return order;
    }

    // ==== Courier (docs/modules/COURIER.md §5) =============================================

    /**
     * What the courier module may read to book an order. Unknown or another shop's order → 404.
     */
    public OrderForCourier forCourier(String orderId) {
        Order o = load(currentUser.shopId(), orderId);
        int units = o.getItems().stream().mapToInt(OrderItem::getQuantity).sum();
        return new OrderForCourier(o.getId(), o.displayNumber(), o.getStatus(), o.getCodAmount(),
                o.getCustomer().name(), o.getCustomer().phone(), o.getCustomer().address(), units);
    }

    /**
     * A courier status moves the order, <b>inside the caller's transaction</b> (the shipment update
     * and the order change commit together). The shop and the "who" come from {@link CurrentUser},
     * which the courier webhook fills with the shipment's shop and a courier system actor.
     *
     * <p>Never throws for "does not apply" (an order closed by hand, a cancelled order): it returns
     * {@link CourierOrderEffect.Result#NOT_APPLICABLE} instead, because an exception here would
     * mark the caller's whole transaction for rollback. Real failures (stock corruption) still
     * throw and roll everything back.
     *
     * @param courierName  stored in {@code shipping.courierName} when the order ships
     * @param trackingCode stored in {@code shipping.trackingId}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CourierOrderEffect.Result applyCourierEvent(String orderId, CourierOrderEffect effect, String courierName,
                                                       String trackingCode) {
        String shopId = currentUser.shopId();
        String userId = currentUser.userId();
        String userName = userName(shopId, userId);
        OrderStatus status = load(shopId, orderId).getStatus();
        Extras ship = new Extras(null, new Shipping(OrderRules.text(courierName), OrderRules.text(trackingCode)), null,
                Map.of());
        return switch (effect) {
            case SHIP -> switch (status) {
                case PACKED -> {
                    transitionInTransaction(shopId, userId, userName, orderId, OrderAction.SHIP, ship, false);
                    yield CourierOrderEffect.Result.APPLIED;
                }
                case SHIPPED, DELIVERED, RETURNED -> CourierOrderEffect.Result.ALREADY_DONE;
                default -> CourierOrderEffect.Result.NOT_APPLICABLE;
            };
            case DELIVER -> switch (status) {
                case PACKED, SHIPPED -> {
                    if (status == OrderStatus.PACKED) {
                        transitionInTransaction(shopId, userId, userName, orderId, OrderAction.SHIP, ship, false);
                    }
                    transitionInTransaction(shopId, userId, userName, orderId, OrderAction.DELIVER, Extras.note(null), false);
                    yield CourierOrderEffect.Result.APPLIED;
                }
                case DELIVERED -> CourierOrderEffect.Result.ALREADY_DONE;
                default -> CourierOrderEffect.Result.NOT_APPLICABLE;
            };
        };
    }

    /** Stock and customer counters for one transition. Runs inside the transaction. */
    private void applySideEffects(Order order, OrderAction action, OrderStatus from, List<StockLine> restockLines) {
        List<StockLine> lines = order.getItems().stream()
                .map(i -> new StockLine(i.getVariantId(), i.getQuantity()))
                .toList();
        OrderRef ref = new OrderRef(order.getId(), order.displayNumber());
        switch (action) {
            case CONFIRM -> inventoryService.reserve(lines, ref);
            case PACK -> {
                // no stock change: the units stay reserved
            }
            case SHIP -> inventoryService.deduct(lines, ref);
            case DELIVER -> customerService.recordDelivered(order.getCustomerId(), order.getTotal());
            case RETURN -> {
                if (!restockLines.isEmpty()) {
                    inventoryService.restockReturn(restockLines, ref);
                }
                customerService.recordReturned(order.getCustomerId());
            }
            case CANCEL -> {
                if (from == OrderStatus.CONFIRMED || from == OrderStatus.PACKED) {
                    inventoryService.release(lines, ref);
                }
                customerService.recordCancelled(order.getCustomerId());
            }
        }
    }

    /**
     * Which items go back on the shelf, and the {@code returnedRestocked} flag per item in the
     * update. A variant in the request that is not in the order → 400.
     */
    private static List<StockLine> returnLines(Order order, Map<String, Boolean> restock, Update update) {
        Set<String> inOrder = new HashSet<>();
        order.getItems().forEach(i -> inOrder.add(i.getVariantId()));
        int index = 0;
        for (String variantId : restock.keySet()) {
            if (!inOrder.contains(variantId)) {
                throw new FieldValidationException("items[" + index + "].variantId", "is not in this order");
            }
            index++;
        }
        List<StockLine> lines = new ArrayList<>();
        for (int i = 0; i < order.getItems().size(); i++) {
            OrderItem item = order.getItems().get(i);
            boolean back = restock.getOrDefault(item.getVariantId(), Boolean.TRUE);
            update.set("items." + i + ".returnedRestocked", back);
            if (back) {
                lines.add(new StockLine(item.getVariantId(), item.getQuantity()));
            }
        }
        return lines;
    }

    /**
     * After a failed confirm (everything rolled back): which items are short, all of them, as field
     * errors {@code items[i].quantity}, so the moderator sees every problem at once.
     */
    private AppException shortageReport(Order order, AppException original) {
        Map<String, Integer> available = inventoryService.availableFor(
                order.getItems().stream().map(OrderItem::getVariantId).toList());
        List<ApiError.FieldError> errors = new ArrayList<>();
        for (int i = 0; i < order.getItems().size(); i++) {
            OrderItem item = order.getItems().get(i);
            int now = available.getOrDefault(item.getVariantId(), 0);
            if (now < item.getQuantity()) {
                errors.add(new ApiError.FieldError("items[" + i + "].quantity",
                        item.getSku() + ": requested " + item.getQuantity() + ", available " + now));
            }
        }
        if (errors.isEmpty()) {
            // Stock came back between the failure and this check: report what the reservation saw.
            return OrderErrors.insufficientStock(original.getMessage(), List.of());
        }
        String message = errors.size() == 1 ? "Not enough stock for SKU " + errors.get(0).message()
                : "Not enough stock for " + errors.size() + " items";
        return OrderErrors.insufficientStock(message, errors);
    }

    // ==== Helpers ========================================================================

    private static Set<OrderStatus> parseStatuses(List<String> values) {
        Set<OrderStatus> statuses = EnumSet.noneOf(OrderStatus.class);
        if (values != null) {
            for (String value : values) {
                if (OrderRules.text(value) != null) {
                    statuses.add(parseEnum(OrderStatus.class, value, "status"));
                }
            }
        }
        return statuses;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        String text = OrderRules.text(value);
        if (text == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException e) {
            throw new FieldValidationException(field, "must be one of " + Arrays.toString(type.getEnumConstants()));
        }
    }

    private static boolean parseAscending(String sort) {
        String text = OrderRules.text(sort);
        if (text == null || "createdAt,desc".equals(text)) {
            return false;
        }
        if ("createdAt,asc".equals(text)) {
            return true;
        }
        throw new FieldValidationException("sort", "must be createdAt,desc or createdAt,asc");
    }

    private Order load(String shopId, String orderId) {
        if (orderId == null || !ObjectId.isValid(orderId)) {
            throw OrderErrors.orderNotFound();
        }
        return orderRepository.findByIdAndShopId(orderId, shopId).orElseThrow(OrderErrors::orderNotFound);
    }

    /** The response, with live {@code available} per item only while PENDING and with STOCK_VIEW. */
    private OrderResponse respond(Order order) {
        Map<String, Integer> available = order.getStatus() == OrderStatus.PENDING
                && currentUser.hasPermission(Permission.STOCK_VIEW)
                ? inventoryService.availableFor(order.getItems().stream().map(OrderItem::getVariantId).toList())
                : Map.of();
        return mapper.toResponse(order, available);
    }

    /** Same {@code requestId} again: the same order if the request is the same, else 409. */
    private CreateResult replay(Order earlier, CreateOrderRequest request) {
        boolean sameCustomer = OrderRules.text(request.customerId()) != null
                ? request.customerId().equals(earlier.getCustomerId())
                : BdPhone.tryNormalize(request.customer().phone()).map(earlier.getCustomer().phone()::equals).orElse(false);
        boolean sameItems = earlier.getItems().size() == request.items().size();
        for (int i = 0; sameItems && i < request.items().size(); i++) {
            OrderItemRequest line = request.items().get(i);
            OrderItem stored = earlier.getItems().get(i);
            sameItems = stored.getVariantId().equals(line.variantId()) && stored.getQuantity() == line.quantity()
                    && (line.unitPrice() == null || line.unitPrice() == stored.getUnitPrice());
        }
        boolean sameMoney = earlier.getDiscount() == Objects.requireNonNullElse(request.discount(), 0L)
                && earlier.getAdvancePaid() == Objects.requireNonNullElse(request.advancePaid(), 0L)
                && (request.deliveryCharge() == null || request.deliveryCharge() == earlier.getDeliveryCharge());
        if (!sameCustomer || !sameItems || !sameMoney) {
            throw OrderErrors.requestIdReused();
        }
        return new CreateResult(respond(earlier), false);
    }

    /**
     * The delivery address: the request's {@code deliveryAddress}, else the inline customer's
     * address, else the customer's saved default address. None at all → 400.
     */
    private Address deliveryAddress(CreateOrderRequest request, OrderCustomer knownCustomer) {
        if (request.deliveryAddress() != null) {
            return request.deliveryAddress().toModel();
        }
        if (request.customer() != null && request.customer().address() != null) {
            return request.customer().address().toModel();
        }
        CustomerResponse.AddressResponse saved = null;
        if (knownCustomer != null) {
            saved = knownCustomer.defaultAddress();
        } else {
            try {
                saved = customerService.lookup(request.customer().phone()).addresses().stream()
                        .filter(CustomerResponse.AddressResponse::isDefault).findFirst().orElse(null);
            } catch (ResourceNotFoundException e) {
                saved = null; // new customer without an address
            }
        }
        if (saved == null) {
            throw new FieldValidationException("deliveryAddress", "is required: the customer has no saved address");
        }
        return new Address(saved.line(), saved.area(), saved.district(), saved.zone(), saved.note());
    }

    private static void requireUniqueVariants(List<OrderItemRequest> items) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            if (!seen.add(items.get(i).variantId())) {
                throw new FieldValidationException("items[" + i + "].variantId",
                        "is already in this order; change the quantity instead");
            }
        }
    }

    /** New items must be an active variant of an active product (docs/modules/ORDER.md §9). */
    private static void requireOrderable(List<OrderItemRequest> items, Map<String, OrderableVariant> newVariants) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            OrderableVariant v = newVariants.get(items.get(i).variantId());
            if (v != null && !v.orderable()) {
                errors.add(new ApiError.FieldError("items[" + i + "].variantId", v.active()
                        ? v.sku() + ": the product is archived"
                        : v.sku() + ": the variant is inactive"));
            }
        }
        if (!errors.isEmpty()) {
            throw OrderErrors.notOrderable(errors);
        }
    }

    /**
     * Item snapshots in request order. An item already in the order keeps its snapshot (and price,
     * unless a new one is sent); a new item copies the variant as it is now.
     */
    private static List<OrderItem> buildItems(List<OrderItemRequest> lines, Map<String, OrderableVariant> variants,
                                              Map<String, OrderItem> existing) {
        List<OrderItem> items = new ArrayList<>();
        for (OrderItemRequest line : lines) {
            OrderItem old = existing.get(line.variantId());
            OrderItem item = new OrderItem();
            if (old != null) {
                item.setProductId(old.getProductId());
                item.setVariantId(old.getVariantId());
                item.setSku(old.getSku());
                item.setProductName(old.getProductName());
                item.setVariantName(old.getVariantName());
                item.setUnitPrice(line.unitPrice() != null ? line.unitPrice() : old.getUnitPrice());
            } else {
                OrderableVariant v = variants.get(line.variantId());
                item.setProductId(v.productId());
                item.setVariantId(v.variantId());
                item.setSku(v.sku());
                item.setProductName(v.productName());
                item.setVariantName(v.variantName());
                item.setUnitPrice(line.unitPrice() != null ? line.unitPrice() : v.price());
            }
            item.setQuantity(line.quantity());
            item.setLineTotal(OrderRules.lineTotal(item.getUnitPrice(), item.getQuantity()));
            items.add(item);
        }
        return items;
    }

    private static List<String> variantIds(List<OrderItemRequest> items) {
        return items.stream().map(OrderItemRequest::variantId).toList();
    }

    private static List<Long> lineTotals(List<OrderItem> items) {
        return items.stream().map(OrderItem::getLineTotal).toList();
    }

    private static void applyTotals(Order order, OrderRules.Totals t) {
        order.setSubtotal(t.subtotal());
        order.setDiscount(t.discount());
        order.setDeliveryCharge(t.deliveryCharge());
        order.setTotal(t.total());
        order.setAdvancePaid(t.advancePaid());
        order.setCodAmount(t.codAmount());
    }

    private static List<String> changedFields(Order order, OrderCustomerSnapshot customer, List<OrderItem> items,
                                              OrderRules.Totals totals, UpdateOrderRequest request, String note) {
        List<String> fields = new ArrayList<>();
        if (!Objects.equals(order.getCustomer().name(), customer.name())) {
            fields.add("customerName");
        }
        if (!Objects.equals(order.getCustomer().address(), customer.address())) {
            fields.add("deliveryAddress");
        }
        if (!sameLines(order.getItems(), items)) {
            fields.add("items");
        }
        if (order.getDiscount() != totals.discount()) {
            fields.add("discount");
        }
        if (order.getDeliveryCharge() != totals.deliveryCharge()) {
            fields.add("deliveryCharge");
        }
        if (order.getAdvancePaid() != totals.advancePaid()) {
            fields.add("advancePaid");
        }
        if (order.getSource() != request.source()) {
            fields.add("source");
        }
        if (!Objects.equals(order.getNote(), note)) {
            fields.add("note");
        }
        return fields;
    }

    private static boolean sameLines(List<OrderItem> a, List<OrderItem> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            OrderItem x = a.get(i);
            OrderItem y = b.get(i);
            if (!x.getVariantId().equals(y.getVariantId()) || x.getQuantity() != y.getQuantity()
                    || x.getUnitPrice() != y.getUnitPrice()) {
                return false;
            }
        }
        return true;
    }

    /** e.g. {@code "+TEE-L ×1, TEE-M qty 1 → 2, −CAP-1"}; {@code null} when the items did not change. */
    static String itemSummary(List<OrderItem> before, List<OrderItem> after) {
        Map<String, OrderItem> old = new LinkedHashMap<>();
        before.forEach(i -> old.put(i.getVariantId(), i));
        List<String> parts = new ArrayList<>();
        for (OrderItem now : after) {
            OrderItem was = old.remove(now.getVariantId());
            if (was == null) {
                parts.add("+" + now.getSku() + " ×" + now.getQuantity());
                continue;
            }
            if (was.getQuantity() != now.getQuantity()) {
                parts.add(now.getSku() + " qty " + was.getQuantity() + " → " + now.getQuantity());
            }
            if (was.getUnitPrice() != now.getUnitPrice()) {
                parts.add(now.getSku() + " price " + was.getUnitPrice() + " → " + now.getUnitPrice());
            }
        }
        old.values().forEach(removed -> parts.add("−" + removed.getSku()));
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static String truncate(String text) {
        return text == null || text.length() <= MAX_SUMMARY ? text : text.substring(0, MAX_SUMMARY - 1) + "…";
    }

    private String userName(String shopId, String userId) {
        // Inside a courier webhook / job: the system actor's name ("Courier (Mock courier)").
        var system = currentUser.systemActor();
        if (system.isPresent()) {
            return system.get().name();
        }
        return userService.findInShop(userId, shopId).map(User::getName).orElse("Unknown user");
    }
}

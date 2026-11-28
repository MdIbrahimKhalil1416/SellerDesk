package com.sellerdesk.courier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sellerdesk.courier.provider.CourierProperties.MockMode;
import com.sellerdesk.courier.provider.mock.MockCourierProvider;
import com.sellerdesk.courier.service.CourierJobs;
import com.sellerdesk.order.OrderTestApi;
import com.sellerdesk.testsupport.MongoIntegrationTestBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;

/**
 * The courier module against a real MongoDB (docs/modules/COURIER.md §9): booking, the order
 * effects of courier statuses (stock, customer counters, history), cancel, the guard on manual
 * ship / cancel, provider failures, the booking reconciler and the tracking sweep.
 */
class ShipmentIntegrationTest extends MongoIntegrationTestBase {

    @Autowired
    private MockCourierProvider mockCourier;

    @Autowired
    private CourierJobs jobs;

    private CourierTestApi api;
    private OrderTestApi orders;
    private TestOwner owner;
    private String variantId;
    private String orderId;

    @BeforeEach
    void setUp() throws Exception {
        mockCourier.reset();
        api = new CourierTestApi(mockMvc);
        orders = new OrderTestApi(mockMvc);
        owner = createVerifiedOwner("courier");
        String[] packed = api.packedOrder(owner.bearer(), "CR-1");
        variantId = packed[0];
        orderId = packed[1];
    }

    @Test
    void sendingAPackedOrderBooksItAndChangesNeitherOrderNorStock() throws Exception {
        Document productBefore = product();
        long movementsBefore = movements();

        String shipment = api.send(owner.bearer(), orderId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.provider").value("MOCK"))
                .andExpect(jsonPath("$.orderNumber").value("SD-1"))
                .andExpect(jsonPath("$.consignmentId").value(org.hamcrest.Matchers.startsWith("MOCK-")))
                .andExpect(jsonPath("$.codAmount").isNumber())
                .andExpect(jsonPath("$.allowedActions").value(org.hamcrest.Matchers.containsInAnyOrder("TRACK", "CANCEL")))
                .andExpect(jsonPath("$.events[0].eventKey").doesNotExist())
                .andExpect(jsonPath("$.shopId").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(OrderTestApi.statusOf(orders.fetch(owner.bearer(), orderId))).isEqualTo("PACKED");
        assertThat(product()).isEqualTo(productBefore);
        assertThat(movements()).isEqualTo(movementsBefore);
        assertThat(mockCourier.parcel(CourierTestApi.consignmentId(shipment))).isPresent();

        // Same courier again: the same shipment, no second parcel.
        api.send(owner.bearer(), orderId).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CourierTestApi.id(shipment)));
        assertThat(mockCourier.parcels()).hasSize(1);
        mockMvc.perform(get("/api/v1/orders/{id}/shipments", orderId).header("Authorization", owner.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void onlyPackedOrdersCanBeSent() throws Exception {
        String pending = orders.createOrder(owner.bearer(), variantId, 1);
        api.send(owner.bearer(), pending).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_PACKED"));
        assertThat(mongoTemplate.count(new Query(), "shipments")).isZero();
    }

    @Test
    void fiveParallelSendsBookOneParcel() throws Exception {
        List<Integer> statuses = runInParallel(5, i -> api.send(owner.bearer(), orderId).andReturn().getResponse().getStatus());
        assertThat(statuses).containsOnly(200, 201).contains(201);
        assertThat(mongoTemplate.count(new Query(), "shipments")).isOne();
        assertThat(mockCourier.parcels()).hasSize(1);
    }

    @Test
    void pickupShipsTheOrderAndDeductsStockAsTheCourier() throws Exception {
        String shipment = api.sendOk(owner.bearer(), orderId);
        String consignment = CourierTestApi.consignmentId(shipment);

        api.webhook(consignment, "picked_up").andExpect(status().isOk()).andExpect(jsonPath("$.result").value("PROCESSED"));

        String order = orders.fetch(owner.bearer(), orderId);
        assertThat(OrderTestApi.statusOf(order)).isEqualTo("SHIPPED");
        assertThat((String) JsonPath.read(order, "$.shipping.courierName")).isEqualTo("Mock courier");
        assertThat((String) JsonPath.read(order, "$.shipping.trackingId")).isEqualTo(JsonPath.read(shipment, "$.trackingCode"));
        assertThat((String) JsonPath.read(order, "$.statusHistory[-1].by.name")).isEqualTo("Courier (Mock courier)");
        Document stock = variantStock();
        assertThat(stock.getInteger("onHand")).isEqualTo(8);
        assertThat(stock.getInteger("reserved")).isZero();
        Document deduct = mongoTemplate.findOne(new Query(Criteria.where("type").is("DEDUCT")), Document.class, "stock_movements");
        assertThat(deduct.getString("createdByName")).isEqualTo("Courier (Mock courier)");
        assertThat(CourierTestApi.statusOf(api.fetch(owner.bearer(), CourierTestApi.id(shipment)))).isEqualTo("PICKED_UP");
    }

    @Test
    void deliveryDeliversTheOrderCountsTheCustomerAndFlagsACodMismatch() throws Exception {
        String shipment = api.sendOk(owner.bearer(), orderId);
        String consignment = CourierTestApi.consignmentId(shipment);
        long cod = ((Number) JsonPath.read(shipment, "$.codAmount")).longValue();

        // Straight from CREATED (pickup webhook lost): ship and deliver in one go.
        api.webhook(CourierTestApi.webhookJson("ev-d", consignment, "delivered",
                        ",\"collectedAmount\":%d,\"courierFee\":6000".formatted(cod - 500)))
                .andExpect(status().isOk());

        assertThat(OrderTestApi.statusOf(orders.fetch(owner.bearer(), orderId))).isEqualTo("DELIVERED");
        Document stats = mongoTemplate.findOne(new Query(), Document.class, "customers").get("stats", Document.class);
        assertThat(stats.getInteger("deliveredOrders")).isEqualTo(1);
        assertThat(variantStock().getInteger("onHand")).isEqualTo(8);
        api.getShipment(owner.bearer(), CourierTestApi.id(shipment))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.collectedAmount").value(cod - 500))
                .andExpect(jsonPath("$.codMismatch").value(true))
                .andExpect(jsonPath("$.courierFee").value(6000))
                .andExpect(jsonPath("$.allowedActions.length()").value(0));
    }

    @Test
    void orderDeliveredByHandIsNotCountedTwiceByALateWebhook() throws Exception {
        String consignment = CourierTestApi.consignmentId(api.sendOk(owner.bearer(), orderId));
        api.webhook(consignment, "picked_up").andExpect(status().isOk());
        orders.actionOk(owner.bearer(), orderId, "deliver");

        api.webhook(consignment, "delivered").andExpect(status().isOk());

        Document stats = mongoTemplate.findOne(new Query(), Document.class, "customers").get("stats", Document.class);
        assertThat(stats.getInteger("deliveredOrders")).isEqualTo(1);
    }

    @Test
    void returnOnlyMarksTheShipmentThenStaffConfirmTheReturn() throws Exception {
        String shipment = api.sendOk(owner.bearer(), orderId);
        String consignment = CourierTestApi.consignmentId(shipment);
        api.webhook(consignment, "picked_up").andExpect(status().isOk());
        api.webhook(consignment, "returned").andExpect(status().isOk());

        assertThat(OrderTestApi.statusOf(orders.fetch(owner.bearer(), orderId))).isEqualTo("SHIPPED");
        assertThat(CourierTestApi.statusOf(api.fetch(owner.bearer(), CourierTestApi.id(shipment)))).isEqualTo("RETURNED");
        mockMvc.perform(get("/api/v1/shipments?status=RETURNED").header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].orderNumber").value("SD-1"));
        mockMvc.perform(get("/api/v1/shipments?status=DELIVERED").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.totalElements").value(0));

        orders.actionOk(owner.bearer(), orderId, "return");
        assertThat(variantStock().getInteger("onHand")).isEqualTo(10);
    }

    @Test
    void cancelBeforePickupKeepsTheOrderPackedAndAllowsANewBooking() throws Exception {
        String first = CourierTestApi.id(api.sendOk(owner.bearer(), orderId));

        mockMvc.perform(CourierTestApi.cancelRequest(first).header("Authorization", owner.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("Customer asked to wait"));
        assertThat(OrderTestApi.statusOf(orders.fetch(owner.bearer(), orderId))).isEqualTo("PACKED");

        String second = CourierTestApi.id(api.sendOk(owner.bearer(), orderId));
        assertThat(second).isNotEqualTo(first);
        api.webhook(CourierTestApi.consignmentId(api.fetch(owner.bearer(), second)), "picked_up");
        mockMvc.perform(CourierTestApi.cancelRequest(second).header("Authorization", owner.bearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIPMENT_NOT_CANCELLABLE"));
    }

    @Test
    void manualShipAndCancelAreBlockedWhileTheOrderIsBooked() throws Exception {
        String shipment = CourierTestApi.id(api.sendOk(owner.bearer(), orderId));

        for (String action : List.of("ship", "cancel")) {
            orders.action(owner.bearer(), orderId, action).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SHIPMENT_ACTIVE"));
        }
        mockMvc.perform(post("/api/v1/orders/bulk-status").header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"SHIP\",\"orderIds\":[\"%s\"]}".formatted(orderId)))
                .andExpect(jsonPath("$.results[0].errorCode").value("SHIPMENT_ACTIVE"));
        assertThat(OrderTestApi.statusOf(orders.fetch(owner.bearer(), orderId))).isEqualTo("PACKED");

        mockMvc.perform(CourierTestApi.cancelRequest(shipment).header("Authorization", owner.bearer())).andExpect(status().isOk());
        orders.actionOk(owner.bearer(), orderId, "cancel");
    }

    @Test
    void courierTimeoutLeavesABookingThatTheReconcilerFinishesWithoutASecondParcel() throws Exception {
        mockCourier.nextCalls(MockMode.TIMEOUT);
        String shipment = api.send(owner.bearer(), orderId)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("BOOKING"))
                .andExpect(jsonPath("$.consignmentId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String id = CourierTestApi.id(shipment);
        assertThat(mockCourier.parcels()).hasSize(1); // the courier has it; we did not hear back

        // Not due yet: nothing happens.
        assertThat(jobs.reconcileBookings()).isZero();
        makeDue(id);
        assertThat(jobs.reconcileBookings()).isOne();

        String after = api.fetch(owner.bearer(), id);
        assertThat(CourierTestApi.statusOf(after)).isEqualTo("CREATED");
        assertThat(CourierTestApi.consignmentId(after)).isEqualTo(mockCourier.parcels().get(0).consignmentId());
        assertThat(mockCourier.parcels()).hasSize(1);
        assertThat(OrderTestApi.statusOf(orders.fetch(owner.bearer(), orderId))).isEqualTo("PACKED");
    }

    @Test
    void rejectedBookingIsFailedAndCanBeSentAgain() throws Exception {
        mockCourier.nextCalls(MockMode.REJECT); // the first call: refused, not a timeout
        api.send(owner.bearer(), orderId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COURIER_REJECTED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("recipient.phone"));
        Document failed = mongoTemplate.findOne(new Query(), Document.class, "shipments");
        assertThat(failed.getString("status")).isEqualTo("FAILED");
        assertThat(failed.getBoolean("active")).isFalse();

        // A rejected booking is not active: the user fixes the order and sends again.
        api.send(owner.bearer(), orderId).andExpect(status().isCreated());
        assertThat(mongoTemplate.count(new Query(), "shipments")).isEqualTo(2);
    }

    @Test
    void timeoutsUntilTheLastAttemptEndFailedWithAManualCheck() throws Exception {
        mockCourier.nextCalls(MockMode.TIMEOUT);
        String id = CourierTestApi.id(api.send(owner.bearer(), orderId).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());
        mockCourier.reset(); // the courier "lost" the parcel: findByMerchantRef finds nothing
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(new ObjectId(id))),
                new Update().set("bookingAttempts", 5), "shipments");
        mockCourier.nextCalls(MockMode.OK, MockMode.TIMEOUT); // lookup: nothing; booking: timeout again
        makeDue(id);

        jobs.reconcileBookings();

        api.getShipment(owner.bearer(), id)
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("COURIER_TIMEOUT"))
                .andExpect(jsonPath("$.needsManualCheck").value(true));
    }

    @Test
    void trackAndTheSweepCatchALostWebhook() throws Exception {
        String shipment = api.sendOk(owner.bearer(), orderId);
        String id = CourierTestApi.id(shipment);
        mockCourier.advance(CourierTestApi.consignmentId(shipment), "picked_up"); // the webhook never came

        // Not silent long enough: the sweep leaves it alone.
        assertThat(jobs.sweepSilentShipments()).isZero();
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(new ObjectId(id))),
                new Update().set("lastEventAt", Date.from(Instant.now().minus(13, ChronoUnit.HOURS))), "shipments");
        assertThat(jobs.sweepSilentShipments()).isOne();
        assertThat(OrderTestApi.statusOf(orders.fetch(owner.bearer(), orderId))).isEqualTo("SHIPPED");

        // The track endpoint applies the courier's view the same way.
        mockCourier.advance(CourierTestApi.consignmentId(shipment), "delivered");
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(new ObjectId(id))), new Update().unset("lastTrackedAt"),
                "shipments");
        mockMvc.perform(post("/api/v1/shipments/{id}/track", id).header("Authorization", owner.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELIVERED"));
        assertThat(OrderTestApi.statusOf(orders.fetch(owner.bearer(), orderId))).isEqualTo("DELIVERED");
    }

    @Test
    void trackWhileTheCourierIsDownIs503AndChangesNothing() throws Exception {
        String id = CourierTestApi.id(api.sendOk(owner.bearer(), orderId));
        Document before = shipmentDoc(id);
        mockCourier.nextCalls(MockMode.TIMEOUT);
        mockMvc.perform(post("/api/v1/shipments/{id}/track", id).header("Authorization", owner.bearer()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("COURIER_UNAVAILABLE"));
        assertThat(shipmentDoc(id)).isEqualTo(before);
    }

    @Test
    void aFailingOrderUpdateRollsBackTheShipmentToo() throws Exception {
        String shipment = api.sendOk(owner.bearer(), orderId);
        String id = CourierTestApi.id(shipment);
        String consignment = CourierTestApi.consignmentId(shipment);
        // Break the stock so the DEDUCT at pickup fails inside the webhook's transaction.
        setReserved(0);
        String json = CourierTestApi.webhookJson("ev-broken", consignment, "picked_up", "");

        api.webhook(json).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        assertThat(shipmentDoc(id).getString("status")).isEqualTo("CREATED");
        assertThat(shipmentDoc(id).getList("events", Document.class)).hasSize(2); // send + booked, nothing more
        assertThat(OrderTestApi.statusOf(orders.fetch(owner.bearer(), orderId))).isEqualTo("PACKED");
        assertThat(mongoTemplate.count(new Query(Criteria.where("type").is("DEDUCT")), "stock_movements")).isZero();

        // The courier retries the same event after the data is fixed: now it goes through, once.
        setReserved(2);
        api.webhook(json).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("PROCESSED"));
        api.webhook(json).andExpect(jsonPath("$.result").value("DUPLICATE"));
        assertThat(OrderTestApi.statusOf(orders.fetch(owner.bearer(), orderId))).isEqualTo("SHIPPED");
    }

    @Test
    void bulkSendReportsEveryOrder() throws Exception {
        String pending = orders.createOrder(owner.bearer(), variantId, 1);
        String[] second = api.packedOrder(owner.bearer(), "CR-2");
        String unknown = new ObjectId().toHexString();
        mockCourier.nextCalls(MockMode.OK, MockMode.REJECT);

        mockMvc.perform(post("/api/v1/shipments/bulk").header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"MOCK\",\"orderIds\":[\"%s\",\"%s\",\"%s\",\"%s\"]}"
                                .formatted(orderId, second[1], pending, unknown)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(1))
                .andExpect(jsonPath("$.failed").value(3))
                .andExpect(jsonPath("$.results[0].ok").value(true))
                .andExpect(jsonPath("$.results[0].status").value("CREATED"))
                .andExpect(jsonPath("$.results[1].errorCode").value("COURIER_REJECTED"))
                .andExpect(jsonPath("$.results[2].errorCode").value("ORDER_NOT_PACKED"))
                .andExpect(jsonPath("$.results[3].errorCode").value("NOT_FOUND"));
    }

    // ---- helpers ------------------------------------------------------------------------

    private void makeDue(String shipmentId) {
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(new ObjectId(shipmentId))),
                new Update().set("nextAttemptAt", Date.from(Instant.now().minusSeconds(5))), "shipments");
    }

    private void setReserved(int reserved) {
        mongoTemplate.updateFirst(new Query(Criteria.where("variants.variantId").is(new ObjectId(variantId))),
                new Update().set("variants.$.stock.reserved", reserved).set("variants.$.stock.available", 10 - reserved),
                "products");
    }

    private Document shipmentDoc(String id) {
        return mongoTemplate.findById(new ObjectId(id), Document.class, "shipments");
    }

    private Document product() {
        return mongoTemplate.findOne(new Query(Criteria.where("variants.variantId").is(new ObjectId(variantId))),
                Document.class, "products");
    }

    private Document variantStock() {
        return product().getList("variants", Document.class).get(0).get("stock", Document.class);
    }

    private long movements() {
        return mongoTemplate.count(new Query(), "stock_movements");
    }

    interface Task<T> {
        T run(int index) throws Exception;
    }

    static <T> List<T> runInParallel(int count, Task<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                Callable<T> callable = () -> {
                    start.await();
                    return task.run(index);
                };
                futures.add(pool.submit(callable));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}

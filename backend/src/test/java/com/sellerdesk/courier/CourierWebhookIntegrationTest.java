package com.sellerdesk.courier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sellerdesk.common.security.TenantContext;
import com.sellerdesk.courier.provider.mock.MockCourierProvider;
import com.sellerdesk.courier.provider.mock.WebhookSigner;
import com.sellerdesk.order.OrderTestApi;
import com.sellerdesk.testsupport.MongoIntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The public webhook endpoint (docs/modules/COURIER.md §5, §9): signature, idempotency, illegal
 * jumps, unknown consignments, and that it needs no token and leaves no tenant behind.
 */
class CourierWebhookIntegrationTest extends MongoIntegrationTestBase {

    @Autowired
    private MockCourierProvider mockCourier;

    private CourierTestApi api;
    private OrderTestApi orders;
    private TestOwner owner;
    private String orderId;
    private String shipmentId;
    private String consignment;

    @BeforeEach
    void bookedOrder() throws Exception {
        mockCourier.reset();
        api = new CourierTestApi(mockMvc);
        orders = new OrderTestApi(mockMvc);
        owner = createVerifiedOwner("webhook");
        orderId = api.packedOrder(owner.bearer(), "WH-1")[1];
        String shipment = api.sendOk(owner.bearer(), orderId);
        shipmentId = CourierTestApi.id(shipment);
        consignment = CourierTestApi.consignmentId(shipment);
    }

    @AfterEach
    void noTenantOrUserLeftOnTheThread() {
        assertThat(TenantContext.getShopId()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void validWebhookNeedsNoTokenAndIsProcessed() throws Exception {
        api.webhook(consignment, "picked_up").andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("PROCESSED"));
        assertThat(shipment().getString("status")).isEqualTo("PICKED_UP");
        assertThat(OrderTestApi.statusOf(orders.fetch(owner.bearer(), orderId))).isEqualTo("SHIPPED");
    }

    @Test
    void theSameEventTwiceChangesNothingTheSecondTime() throws Exception {
        String json = CourierTestApi.webhookJson("ev-1", consignment, "picked_up", "");
        api.webhook(json).andExpect(jsonPath("$.result").value("PROCESSED"));
        Document after = shipment();
        long movements = mongoTemplate.count(new Query(), "stock_movements");

        api.webhook(json).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("DUPLICATE"));

        assertThat(shipment()).isEqualTo(after);
        assertThat(mongoTemplate.count(new Query(), "stock_movements")).isEqualTo(movements);
    }

    @Test
    void anEventWithoutIdIsDeduplicatedByItsContent() throws Exception {
        String json = CourierTestApi.webhookJson(null, consignment, "picked_up", "");
        api.webhook(json).andExpect(jsonPath("$.result").value("PROCESSED"));
        api.webhook(json).andExpect(jsonPath("$.result").value("DUPLICATE"));
    }

    @Test
    void fiveCopiesAtTheSameMomentAreAppliedOnce() throws Exception {
        String json = CourierTestApi.webhookJson("ev-parallel", consignment, "picked_up", "");

        List<String> results = ShipmentIntegrationTest.runInParallel(5, i -> api.webhook(json)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(results.stream().filter(r -> r.contains("PROCESSED")).count()).isOne();
        assertThat(results.stream().filter(r -> r.contains("DUPLICATE")).count()).isEqualTo(4);
        assertThat(mongoTemplate.count(new Query(Criteria.where("type").is("DEDUCT")), "stock_movements")).isOne();
        Document order = mongoTemplate.findById(new ObjectId(orderId), Document.class, "orders");
        assertThat(order.getList("statusHistory", Document.class).stream()
                .filter(h -> "SHIPPED".equals(h.getString("to"))).count()).isOne();
        assertThat(shipment().getList("events", Document.class).stream()
                .filter(e -> "PICKED_UP".equals(e.getString("status"))).count()).isOne();
    }

    @Test
    void badSignaturesAre401AndChangeNothing() throws Exception {
        Document before = shipment();
        String json = CourierTestApi.webhookJson("ev-x", consignment, "delivered", "");
        long now = Instant.now().getEpochSecond();
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        List<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> attempts = List.of(
                CourierTestApi.signed(json, "a-wrong-secret-that-is-long-enough-01234567", now),
                CourierTestApi.signed(json, CourierTestApi.SECRET, now - 600),
                // Signed for one body, sent with another.
                post("/api/v1/webhooks/courier/mock").contentType(MediaType.APPLICATION_JSON)
                        .content(json.replace("delivered", "returned"))
                        .header(WebhookSigner.TIMESTAMP_HEADER, Long.toString(now))
                        .header(WebhookSigner.SIGNATURE_HEADER, WebhookSigner.sign(CourierTestApi.SECRET, now, body)),
                post("/api/v1/webhooks/courier/mock").contentType(MediaType.APPLICATION_JSON).content(json));
        for (var attempt : attempts) {
            mockMvc.perform(attempt).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"))
                    .andExpect(jsonPath("$.message").value("Invalid webhook signature"));
        }
        assertThat(shipment()).isEqualTo(before);
        assertThat(OrderTestApi.statusOf(orders.fetch(owner.bearer(), orderId))).isEqualTo("PACKED");
    }

    @Test
    void unknownConsignmentOrCourierIs404() throws Exception {
        api.webhook("MOCK-0000000000", "picked_up").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(post("/api/v1/webhooks/courier/steadfast").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        assertThat(shipment().getString("status")).isEqualTo("CREATED");
    }

    @Test
    void webhookFasterThanOurBookingWriteIsFoundByMerchantRef() throws Exception {
        api.webhook("""
                {"eventId":"ev-ref","merchantRef":"%s","status":"picked_up","time":"%s"}""".formatted(shipmentId, Instant.now()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("PROCESSED"));
        assertThat(shipment().getString("status")).isEqualTo("PICKED_UP");
    }

    @Test
    void illegalJumpsAreIgnoredAndRecorded() throws Exception {
        // CREATED → RETURNED: nothing can come back that was never picked up.
        api.webhook(consignment, "returned").andExpect(status().isOk()).andExpect(jsonPath("$.result").value("IGNORED"));
        assertThat(shipment().getString("status")).isEqualTo("CREATED");

        api.webhook(consignment, "delivered").andExpect(jsonPath("$.result").value("PROCESSED"));
        // DELIVERED → IN_TRANSIT, and a late PICKED_UP: backwards, ignored.
        api.webhook(consignment, "in_transit").andExpect(jsonPath("$.result").value("IGNORED"));
        api.webhook(consignment, "picked_up").andExpect(jsonPath("$.result").value("IGNORED"));

        assertThat(shipment().getString("status")).isEqualTo("DELIVERED");
        assertThat(OrderTestApi.statusOf(orders.fetch(owner.bearer(), orderId))).isEqualTo("DELIVERED");
        assertThat(shipment().getList("events", Document.class).stream().filter(e -> e.getBoolean("ignored")).count())
                .isEqualTo(3);
        api.getShipment(owner.bearer(), shipmentId).andExpect(jsonPath("$.events[?(@.ignored == true)].note")
                .value(org.hamcrest.Matchers.hasItem("Ignored: DELIVERED → IN_TRANSIT is not allowed")));
    }

    @Test
    void cancelledShipmentCannotBePickedUp() throws Exception {
        mockMvc.perform(CourierTestApi.cancelRequest(shipmentId).header("Authorization", owner.bearer()))
                .andExpect(status().isOk());
        api.webhook(consignment, "picked_up").andExpect(jsonPath("$.result").value("IGNORED"));
        assertThat(OrderTestApi.statusOf(orders.fetch(owner.bearer(), orderId))).isEqualTo("PACKED");
    }

    @Test
    void signedButBrokenBodyIs400() throws Exception {
        api.webhook("{\"status\":\"picked_up\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        api.webhook("not json").andExpect(status().isBadRequest());
    }

    @Test
    void tooLargeBodyIs413() throws Exception {
        String big = "{\"note\":\"" + "x".repeat(70_000) + "\"}";
        api.webhook(big).andExpect(status().isContentTooLarge()).andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    private Document shipment() {
        return mongoTemplate.findById(new ObjectId(shipmentId), Document.class, "shipments");
    }
}

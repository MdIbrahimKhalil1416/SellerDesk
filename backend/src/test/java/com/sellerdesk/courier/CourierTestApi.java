package com.sellerdesk.courier;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sellerdesk.courier.provider.mock.WebhookSigner;
import com.sellerdesk.inventory.InventoryTestApi;
import com.sellerdesk.order.OrderTestApi;
import com.sellerdesk.product.ProductTestApi;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Small helpers to call the shipment API and send signed mock webhooks in integration tests. */
public final class CourierTestApi {

    /** Same value as {@code sellerdesk.courier.webhook-secret} in application-test.yml. */
    public static final String SECRET = "test-only-courier-webhook-secret-0123456789";

    private final MockMvc mockMvc;

    public CourierTestApi(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    /** A product with stock 10 and a PACKED order of 2 units of it; returns {variantId, orderId}. */
    public String[] packedOrder(String bearer, String sku) throws Exception {
        String variantId = ProductTestApi.variantId(new ProductTestApi(mockMvc).createOk(bearer, "Courier Tee " + sku, sku), 0);
        new InventoryTestApi(mockMvc).restockOk(bearer, variantId, 10);
        OrderTestApi orders = new OrderTestApi(mockMvc);
        String orderId = orders.createOrder(bearer, variantId, 2);
        orders.actionOk(bearer, orderId, "confirm");
        orders.actionOk(bearer, orderId, "pack");
        return new String[] {variantId, orderId};
    }

    public static MockHttpServletRequestBuilder sendRequest(String orderId, String provider) {
        return post("/api/v1/orders/{id}/shipment", orderId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"%s\",\"note\":\"Call first\"}".formatted(provider));
    }

    public ResultActions send(String bearer, String orderId) throws Exception {
        return mockMvc.perform(sendRequest(orderId, "MOCK").header("Authorization", bearer));
    }

    /** Sends the order (expects 201) and returns the shipment JSON. */
    public String sendOk(String bearer, String orderId) throws Exception {
        return send(bearer, orderId).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    public static MockHttpServletRequestBuilder cancelRequest(String shipmentId) {
        return post("/api/v1/shipments/{id}/cancel", shipmentId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Customer asked to wait\"}");
    }

    public ResultActions getShipment(String bearer, String shipmentId) throws Exception {
        return mockMvc.perform(get("/api/v1/shipments/{id}", shipmentId).header("Authorization", bearer));
    }

    public String fetch(String bearer, String shipmentId) throws Exception {
        return getShipment(bearer, shipmentId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    // ---- Webhooks ------------------------------------------------------------------------

    /** A mock webhook body; {@code extra} must start with a comma or be empty. */
    public static String webhookJson(String eventId, String consignmentId, String status, String extra) {
        return "{\"eventId\":%s,\"consignmentId\":\"%s\",\"status\":\"%s\",\"time\":\"%s\"%s}".formatted(
                eventId == null ? "null" : "\"" + eventId + "\"", consignmentId, status, Instant.now(), extra);
    }

    /** POST with a correct signature. */
    public ResultActions webhook(String json) throws Exception {
        return mockMvc.perform(signed(json, SECRET, Instant.now().getEpochSecond()));
    }

    /** POST a new event for this consignment (fresh event id). */
    public ResultActions webhook(String consignmentId, String status) throws Exception {
        return webhook(webhookJson(UUID.randomUUID().toString(), consignmentId, status, ""));
    }

    public static MockHttpServletRequestBuilder signed(String json, String secret, long timestamp) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return post("/api/v1/webhooks/courier/mock").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(WebhookSigner.TIMESTAMP_HEADER, Long.toString(timestamp))
                .header(WebhookSigner.SIGNATURE_HEADER, WebhookSigner.sign(secret, timestamp, body));
    }

    public static String id(String json) {
        return JsonPath.read(json, "$.id");
    }

    public static String consignmentId(String json) {
        return JsonPath.read(json, "$.consignmentId");
    }

    public static String statusOf(String json) {
        return JsonPath.read(json, "$.status");
    }
}

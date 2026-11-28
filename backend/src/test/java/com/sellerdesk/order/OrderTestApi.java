package com.sellerdesk.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Small helpers to call the order API in integration tests. */
public final class OrderTestApi {

    public static final String PHONE = "01712345678";

    private final MockMvc mockMvc;

    public OrderTestApi(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    /** One item line: {@code {"variantId":"...","quantity":2}}. */
    public static String line(String variantId, int quantity) {
        return "{\"variantId\":\"%s\",\"quantity\":%d}".formatted(variantId, quantity);
    }

    /** An order for an inline customer (phone {@link #PHONE}, inside Dhaka) with the given item lines. */
    public static String orderJson(String... lines) {
        return orderJson(UUID.randomUUID().toString(), PHONE, "INSIDE_DHAKA", List.of(lines), "");
    }

    /**
     * @param extra more JSON fields, e.g. {@code ,"discount":100}; must start with a comma or be empty
     */
    public static String orderJson(String requestId, String phone, String zone, List<String> lines, String extra) {
        return """
                {"requestId":"%s",
                 "customer":{"name":"Rahim","phone":"%s",
                   "address":{"line":"House 5, Road 3","area":"Mirpur","district":"Dhaka","zone":"%s"}},
                 "items":[%s],"source":"MESSENGER"%s}"""
                .formatted(requestId, phone, zone, String.join(",", lines), extra);
    }

    public ResultActions create(String bearer, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/orders").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** Creates the order (expects 201) and returns the response body. */
    public String createOk(String bearer, String json) throws Exception {
        return create(bearer, json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    /** Creates an order of {@code quantity} × {@code variantId} and returns its id. */
    public String createOrder(String bearer, String variantId, int quantity) throws Exception {
        return id(createOk(bearer, orderJson(line(variantId, quantity))));
    }

    public ResultActions getOrder(String bearer, String orderId) throws Exception {
        return mockMvc.perform(get("/api/v1/orders/{id}", orderId).header("Authorization", bearer));
    }

    public String fetch(String bearer, String orderId) throws Exception {
        return getOrder(bearer, orderId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** {@code POST /orders/{id}/<action>} with a body that is valid for every action. */
    public static MockHttpServletRequestBuilder actionRequest(String orderId, String action) {
        String body = switch (action) {
            case "cancel" -> "{\"reason\":\"Customer changed mind\"}";
            case "return" -> "{\"reason\":\"Customer refused\"}";
            case "ship" -> "{\"courierName\":\"Steadfast\",\"trackingId\":\"TRK-1\"}";
            default -> "{}";
        };
        return post("/api/v1/orders/{id}/" + action, orderId).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    public ResultActions action(String bearer, String orderId, String action) throws Exception {
        return mockMvc.perform(actionRequest(orderId, action).header("Authorization", bearer));
    }

    public void actionOk(String bearer, String orderId, String action) throws Exception {
        action(bearer, orderId, action).andExpect(status().isOk());
    }

    /** {@code PUT /orders/{id}} with the given lines, inside Dhaka, and extra JSON fields. */
    public static MockHttpServletRequestBuilder updateRequest(String orderId, long version, List<String> lines, String extra) {
        String json = """
                {"deliveryAddress":{"line":"House 9","district":"Dhaka","zone":"INSIDE_DHAKA"},
                 "items":[%s],"source":"PHONE","version":%d%s}""".formatted(String.join(",", lines), version, extra);
        return put("/api/v1/orders/{id}", orderId).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    /** The actions that bring a new (PENDING) order to {@code status}. */
    public static List<String> pathTo(String status) {
        List<String> path = new ArrayList<>();
        switch (status) {
            case "PENDING" -> { }
            case "CONFIRMED" -> path.add("confirm");
            case "PACKED" -> path.addAll(List.of("confirm", "pack"));
            case "SHIPPED" -> path.addAll(List.of("confirm", "pack", "ship"));
            case "DELIVERED" -> path.addAll(List.of("confirm", "pack", "ship", "deliver"));
            case "RETURNED" -> path.addAll(List.of("confirm", "pack", "ship", "return"));
            case "CANCELLED" -> path.add("cancel");
            default -> throw new IllegalArgumentException(status);
        }
        return path;
    }

    public static String id(String json) {
        return JsonPath.read(json, "$.id");
    }

    public static String statusOf(String json) {
        return JsonPath.read(json, "$.status");
    }

    public static long version(String json) {
        return ((Number) JsonPath.read(json, "$.version")).longValue();
    }
}

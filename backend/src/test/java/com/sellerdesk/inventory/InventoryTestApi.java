package com.sellerdesk.inventory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Small helpers to call the inventory API in integration tests. */
public final class InventoryTestApi {

    private final MockMvc mockMvc;

    public InventoryTestApi(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    public static String restockJson(int quantity, String requestId) {
        return "{\"quantity\":%d,\"requestId\":\"%s\"}".formatted(quantity, requestId);
    }

    public static String adjustJson(int change, String reason, String note, String requestId) {
        String noteJson = note == null ? "null" : "\"" + note + "\"";
        return "{\"quantityChange\":%d,\"reasonCode\":\"%s\",\"note\":%s,\"requestId\":\"%s\"}"
                .formatted(change, reason, noteJson, requestId);
    }

    public static MockHttpServletRequestBuilder restockRequest(String variantId, String json) {
        return post("/api/v1/inventory/variants/{id}/restock", variantId)
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    public static MockHttpServletRequestBuilder adjustRequest(String variantId, String json) {
        return post("/api/v1/inventory/variants/{id}/adjustments", variantId)
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    public ResultActions restock(String bearer, String variantId, int quantity) throws Exception {
        return mockMvc.perform(restockRequest(variantId, restockJson(quantity, UUID.randomUUID().toString()))
                .header("Authorization", bearer));
    }

    public ResultActions adjust(String bearer, String variantId, int change, String reason, String note) throws Exception {
        return mockMvc.perform(adjustRequest(variantId, adjustJson(change, reason, note, UUID.randomUUID().toString()))
                .header("Authorization", bearer));
    }

    /** Restock and expect 201. */
    public void restockOk(String bearer, String variantId, int quantity) throws Exception {
        restock(bearer, variantId, quantity).andExpect(status().isCreated());
    }
}

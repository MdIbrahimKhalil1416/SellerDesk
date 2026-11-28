package com.sellerdesk.order;

import static com.sellerdesk.order.OrderTestApi.line;
import static com.sellerdesk.order.OrderTestApi.orderJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import com.sellerdesk.inventory.InventoryTestApi;
import com.sellerdesk.product.ProductTestApi;
import com.sellerdesk.testsupport.MongoIntegrationTestBase;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The HTTP layer of {@code OrderController}: every endpoint checks its own permission
 * (docs/SECURITY.md §3). A user missing it gets {@code 403} and nothing changes; a user with only
 * that permission gets past the check.
 */
class OrderControllerTest extends MongoIntegrationTestBase {

    private TestOwner owner;
    private String variantId;
    private String orderId;

    @BeforeEach
    void setUp() throws Exception {
        owner = createVerifiedOwner("order-http");
        variantId = ProductTestApi.variantId(new ProductTestApi(mockMvc).createOk(owner.bearer(), "Http Tee", "HTTP-1"), 0);
        new InventoryTestApi(mockMvc).restockOk(owner.bearer(), variantId, 10);
        orderId = new OrderTestApi(mockMvc).createOrder(owner.bearer(), variantId, 1);
    }

    /** Each endpoint with a body that is valid for it, and the permission it needs. */
    private Map<Permission, List<Supplier<MockHttpServletRequestBuilder>>> endpoints() {
        String json = MediaType.APPLICATION_JSON_VALUE;
        return Map.of(
                Permission.ORDER_CREATE, List.of(() -> post("/api/v1/orders").contentType(json)
                        .content(orderJson(line(variantId, 1)))),
                Permission.ORDER_VIEW, List.of(() -> get("/api/v1/orders/{id}", orderId), () -> get("/api/v1/orders"),
                        () -> get("/api/v1/orders/{id}/timeline", orderId)),
                Permission.ORDER_EDIT, List.of(() -> OrderTestApi.updateRequest(orderId, 0, List.of(line(variantId, 2)), "")),
                Permission.ORDER_CONFIRM, List.of(() -> OrderTestApi.actionRequest(orderId, "confirm")),
                Permission.ORDER_FULFILL, List.of(() -> OrderTestApi.actionRequest(orderId, "pack"),
                        () -> OrderTestApi.actionRequest(orderId, "ship")),
                Permission.ORDER_CLOSE, List.of(() -> OrderTestApi.actionRequest(orderId, "deliver"),
                        () -> OrderTestApi.actionRequest(orderId, "return")),
                Permission.ORDER_CANCEL, List.of(() -> OrderTestApi.actionRequest(orderId, "cancel")));
    }

    @Test
    void everyEndpointIs403WithoutItsPermissionAndNothingChanges() throws Exception {
        Document before = orderDocument();
        for (var entry : endpoints().entrySet()) {
            EnumSet<Permission> allButOne = EnumSet.allOf(Permission.class);
            allButOne.remove(entry.getKey());
            String bearer = bearerForStaff(owner.shopId(), Role.MODERATOR, allButOne);
            for (Supplier<MockHttpServletRequestBuilder> request : entry.getValue()) {
                mockMvc.perform(request.get().header("Authorization", bearer))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            }
        }
        assertThat(orderDocument()).isEqualTo(before);
        assertThat(mongoTemplate.count(new org.springframework.data.mongodb.core.query.Query(), "orders")).isOne();
    }

    @Test
    void thePermissionAloneIsEnoughToPassTheCheck() throws Exception {
        for (var entry : endpoints().entrySet()) {
            String bearer = bearerForStaff(owner.shopId(), Role.MODERATOR, EnumSet.of(entry.getKey()));
            for (Supplier<MockHttpServletRequestBuilder> request : entry.getValue()) {
                int status = mockMvc.perform(request.get().header("Authorization", bearer)).andReturn().getResponse().getStatus();
                // 200/201, or 409 because the order is not in the right status: but never 403.
                assertThat(status).as(entry.getKey().name()).isIn(200, 201, 409);
            }
        }
    }

    @Test
    void bulkNeedsThePermissionOfItsAction() throws Exception {
        String json = MediaType.APPLICATION_JSON_VALUE;
        String confirmAll = "{\"action\":\"CONFIRM\",\"orderIds\":[\"%s\"]}".formatted(orderId);
        String viewer = bearerForStaff(owner.shopId(), Role.MODERATOR, EnumSet.of(Permission.ORDER_VIEW));
        String packer = bearerForStaff(owner.shopId(), Role.PACKER); // ORDER_FULFILL, not ORDER_CONFIRM
        Document before = orderDocument();

        for (String bearer : List.of(viewer, packer)) {
            mockMvc.perform(post("/api/v1/orders/bulk-status").header("Authorization", bearer).contentType(json)
                            .content(confirmAll))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        assertThat(orderDocument()).isEqualTo(before);

        String confirmer = bearerForStaff(owner.shopId(), Role.MODERATOR, EnumSet.of(Permission.ORDER_CONFIRM));
        mockMvc.perform(post("/api/v1/orders/bulk-status").header("Authorization", confirmer).contentType(json)
                        .content(confirmAll))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(1));
        mockMvc.perform(post("/api/v1/orders/bulk-status").header("Authorization", packer).contentType(json)
                        .content("{\"action\":\"PACK\",\"orderIds\":[\"%s\"]}".formatted(orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("PACKED"));
    }

    @Test
    void roleDefaultsMatchTheirJobs() throws Exception {
        String packer = bearerForStaff(owner.shopId(), Role.PACKER);
        String accountant = bearerForStaff(owner.shopId(), Role.ACCOUNTANT);
        String moderator = bearerForStaff(owner.shopId(), Role.MODERATOR);

        for (String action : List.of("confirm", "cancel", "deliver")) {
            mockMvc.perform(OrderTestApi.actionRequest(orderId, action).header("Authorization", packer))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/v1/orders").header("Authorization", packer).contentType(MediaType.APPLICATION_JSON)
                .content(orderJson(line(variantId, 1)))).andExpect(status().isForbidden());
        // The packer may look at the order, but sees no action until it is confirmed.
        mockMvc.perform(get("/api/v1/orders/{id}", orderId).header("Authorization", packer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedActions").isEmpty());

        mockMvc.perform(OrderTestApi.actionRequest(orderId, "confirm").header("Authorization", moderator))
                .andExpect(status().isOk());
        mockMvc.perform(OrderTestApi.actionRequest(orderId, "pack").header("Authorization", moderator))
                .andExpect(status().isForbidden());
        mockMvc.perform(OrderTestApi.actionRequest(orderId, "pack").header("Authorization", accountant))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/orders/{id}", orderId).header("Authorization", packer))
                .andExpect(jsonPath("$.allowedActions").value(org.hamcrest.Matchers.contains("PACK")));
        mockMvc.perform(OrderTestApi.actionRequest(orderId, "pack").header("Authorization", packer))
                .andExpect(status().isOk());
        mockMvc.perform(OrderTestApi.actionRequest(orderId, "ship").header("Authorization", packer))
                .andExpect(status().isOk());
        mockMvc.perform(OrderTestApi.actionRequest(orderId, "deliver").header("Authorization", accountant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test
    void orderCreateAloneCanTakeAnOrderForANewCustomer() throws Exception {
        String orderTaker = bearerForStaff(owner.shopId(), Role.MODERATOR, EnumSet.of(Permission.ORDER_CREATE));
        mockMvc.perform(post("/api/v1/orders").header("Authorization", orderTaker).contentType(MediaType.APPLICATION_JSON)
                        .content(OrderTestApi.orderJson(java.util.UUID.randomUUID().toString(), "01912345678", "INSIDE_DHAKA",
                                List.of(line(variantId, 1)), "")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].available").doesNotExist()) // no STOCK_VIEW
                .andExpect(jsonPath("$.allowedActions").isEmpty());
    }

    @Test
    void withoutTokenIs401AndMalformedIdIs404() throws Exception {
        mockMvc.perform(get("/api/v1/orders/{id}", orderId)).andExpect(status().isUnauthorized());
        mockMvc.perform(OrderTestApi.actionRequest(orderId, "confirm")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/orders/{id}", "not-an-id").header("Authorization", owner.bearer()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/orders/{id}", new ObjectId().toHexString()).header("Authorization", owner.bearer()))
                .andExpect(status().isNotFound());
    }

    private Document orderDocument() {
        return mongoTemplate.findById(new ObjectId(orderId), Document.class, "orders");
    }
}

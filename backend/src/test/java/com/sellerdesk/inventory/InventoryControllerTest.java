package com.sellerdesk.inventory;

import static com.sellerdesk.inventory.InventoryTestApi.adjustJson;
import static com.sellerdesk.inventory.InventoryTestApi.adjustRequest;
import static com.sellerdesk.inventory.InventoryTestApi.restockJson;
import static com.sellerdesk.inventory.InventoryTestApi.restockRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import com.sellerdesk.product.ProductTestApi;
import com.sellerdesk.testsupport.MongoIntegrationTestBase;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The HTTP layer of {@code InventoryController}: {@code @PreAuthorize} per role ({@code 403}),
 * Bean Validation and parameter checks ({@code 400}), and the error format. Real users per role,
 * inserted directly (docs/SECURITY.md §3: every role has STOCK_VIEW, only the owner STOCK_ADJUST).
 */
class InventoryControllerTest extends MongoIntegrationTestBase {

    private TestOwner owner;
    private String productId;
    private String variantId;

    @BeforeEach
    void setUp() throws Exception {
        owner = createVerifiedOwner("inv-http");
        String created = new ProductTestApi(mockMvc).createOk(owner.bearer(), "Tee", "TEE-1");
        productId = ProductTestApi.id(created);
        variantId = ProductTestApi.variantId(created, 0);
        new InventoryTestApi(mockMvc).restockOk(owner.bearer(), variantId, 5);
    }

    private List<MockHttpServletRequestBuilder> writeRequests() {
        return List.of(
                restockRequest(variantId, restockJson(1, UUID.randomUUID().toString())),
                adjustRequest(variantId, adjustJson(-1, "DAMAGED", null, UUID.randomUUID().toString())));
    }

    private List<MockHttpServletRequestBuilder> readRequests() {
        return List.of(
                get("/api/v1/inventory"),
                get("/api/v1/inventory/low-stock"),
                get("/api/v1/inventory/variants/{v}", variantId),
                get("/api/v1/inventory/movements"));
    }

    // ==== Permissions =====================================================================

    @Nested
    class Permissions {

        @Test
        void staffRolesCanReadButNotChangeStock() throws Exception {
            for (Role role : List.of(Role.MODERATOR, Role.PACKER, Role.ACCOUNTANT)) {
                String bearer = bearerForStaff(owner.shopId(), role);
                for (MockHttpServletRequestBuilder read : readRequests()) {
                    mockMvc.perform(read.header("Authorization", bearer)).andExpect(status().isOk());
                }
                for (MockHttpServletRequestBuilder write : writeRequests()) {
                    mockMvc.perform(write.header("Authorization", bearer))
                            .andExpect(status().isForbidden())
                            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
                }
            }
            assertThat(stock()).isEqualTo(List.of(5, 0, 5));
            assertThat(mongoTemplate.count(new Query(), "stock_movements")).isOne();
        }

        @Test
        void staffWithStockAdjustCanChangeStock() throws Exception {
            String bearer = bearerForStaff(owner.shopId(), Role.MODERATOR,
                    EnumSet.of(Permission.STOCK_VIEW, Permission.STOCK_ADJUST));
            for (MockHttpServletRequestBuilder write : writeRequests()) {
                mockMvc.perform(write.header("Authorization", bearer)).andExpect(status().isCreated());
            }
        }

        @Test
        void withoutStockViewEveryReadIs403() throws Exception {
            String bearer = bearerForStaff(owner.shopId(), Role.MODERATOR, EnumSet.of(Permission.PRODUCT_VIEW));
            for (MockHttpServletRequestBuilder read : readRequests()) {
                mockMvc.perform(read.header("Authorization", bearer)).andExpect(status().isForbidden());
            }
        }

        @Test
        void platformAdminIs403AndNoTokenIs401() throws Exception {
            String admin = bearerForStaff(null, Role.PLATFORM_ADMIN, Set.of());
            for (MockHttpServletRequestBuilder request : readRequests()) {
                mockMvc.perform(request.header("Authorization", admin)).andExpect(status().isForbidden());
            }
            for (MockHttpServletRequestBuilder request : writeRequests()) {
                mockMvc.perform(request.header("Authorization", admin)).andExpect(status().isForbidden());
            }
            mockMvc.perform(get("/api/v1/inventory")).andExpect(status().isUnauthorized());
        }
    }

    // ==== Validation ======================================================================

    @Nested
    class Validation {

        private String uuid() {
            return UUID.randomUUID().toString();
        }

        @Test
        void restockQuantityMustBe1To100000() throws Exception {
            expectFieldError(restockRequest(variantId, restockJson(0, uuid())), "quantity");
            expectFieldError(restockRequest(variantId, restockJson(100_001, uuid())), "quantity");
            expectFieldError(restockRequest(variantId, "{\"requestId\":\"" + uuid() + "\"}"), "quantity");
        }

        @Test
        void requestIdIsRequiredAndMustBeAUuid() throws Exception {
            expectFieldError(restockRequest(variantId, "{\"quantity\":1}"), "requestId");
            expectFieldError(restockRequest(variantId, restockJson(1, "not-a-uuid")), "requestId");
        }

        @Test
        void noteIsAtMost200Characters() throws Exception {
            String json = "{\"quantity\":1,\"note\":\"%s\",\"requestId\":\"%s\"}".formatted("x".repeat(201), uuid());
            expectFieldError(restockRequest(variantId, json), "note");
        }

        @Test
        void adjustmentRules() throws Exception {
            expectFieldError(adjustRequest(variantId, adjustJson(0, "DAMAGED", null, uuid())), "quantityChange");
            expectFieldError(adjustRequest(variantId, adjustJson(-100_001, "DAMAGED", null, uuid())), "quantityChange");
            expectFieldError(adjustRequest(variantId, adjustJson(1, "OTHER", null, uuid())), "note");
            expectFieldError(adjustRequest(variantId,
                    "{\"quantityChange\":1,\"requestId\":\"%s\"}".formatted(uuid())), "reasonCode");
            assertThat(stock()).isEqualTo(List.of(5, 0, 5));
        }

        @Test
        void decimalsAndUnknownEnumValuesAreRejected() throws Exception {
            mockMvc.perform(restockRequest(variantId, "{\"quantity\":2.5,\"requestId\":\"%s\"}".formatted(uuid()))
                            .header("Authorization", owner.bearer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
            mockMvc.perform(adjustRequest(variantId, adjustJson(1, "STOLEN_BY_ALIENS", null, uuid()))
                            .header("Authorization", owner.bearer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
            assertThat(stock()).isEqualTo(List.of(5, 0, 5));
        }

        @Test
        void listAndHistoryParametersAreChecked() throws Exception {
            for (String url : List.of(
                    "/api/v1/inventory?stockStatus=SOMETIMES",
                    "/api/v1/inventory?sort=price,asc",
                    "/api/v1/inventory?includeInactive=maybe",
                    "/api/v1/inventory?size=101",
                    "/api/v1/inventory/low-stock?page=-1",
                    "/api/v1/inventory/movements?type=STOLEN",
                    "/api/v1/inventory/movements?reasonCode=STOLEN",
                    "/api/v1/inventory/movements?from=yesterday")) {
                mockMvc.perform(get(url).header("Authorization", owner.bearer()))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            }
        }

        @Test
        void malformedOrUnknownVariantIdIs404() throws Exception {
            for (String id : List.of("not-an-id", new ObjectId().toHexString())) {
                mockMvc.perform(get("/api/v1/inventory/variants/{v}", id).header("Authorization", owner.bearer()))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
                mockMvc.perform(restockRequest(id, restockJson(1, uuid())).header("Authorization", owner.bearer()))
                        .andExpect(status().isNotFound());
            }
            // A product id is not a variant id.
            mockMvc.perform(get("/api/v1/inventory/variants/{v}", productId).header("Authorization", owner.bearer()))
                    .andExpect(status().isNotFound());
        }

        @Test
        void unknownFieldsLikeStockOrTypeAreIgnored() throws Exception {
            String json = "{\"quantity\":1,\"type\":\"ADJUSTMENT\",\"onHand\":999,\"available\":999,\"requestId\":\"%s\"}"
                    .formatted(uuid());
            mockMvc.perform(restockRequest(variantId, json).header("Authorization", owner.bearer()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.movement.type").value("RESTOCK"))
                    .andExpect(jsonPath("$.stock.onHand").value(6));
        }

        private void expectFieldError(MockHttpServletRequestBuilder request, String field) throws Exception {
            mockMvc.perform(request.header("Authorization", owner.bearer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == '" + field + "')]").exists());
        }
    }

    private List<Integer> stock() {
        Document product = mongoTemplate.findById(new ObjectId(productId), Document.class, "products");
        Document s = product.getList("variants", Document.class).get(0).get("stock", Document.class);
        return List.of(s.getInteger("onHand"), s.getInteger("reserved"), s.getInteger("available"));
    }
}

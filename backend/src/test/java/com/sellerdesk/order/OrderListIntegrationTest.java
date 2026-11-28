package com.sellerdesk.order;

import static com.sellerdesk.order.OrderTestApi.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sellerdesk.inventory.InventoryTestApi;
import com.sellerdesk.order.model.OrderSource;
import com.sellerdesk.order.model.OrderStatus;
import com.sellerdesk.order.repository.OrderQueryRepository;
import com.sellerdesk.product.ProductTestApi;
import com.sellerdesk.testsupport.MongoIntegrationTestBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Order list (filters, paging, sorting, index use), bulk status change and the timeline against a
 * real MongoDB (docs/modules/ORDER.md §8, §11).
 */
class OrderListIntegrationTest extends MongoIntegrationTestBase {

    @Autowired
    private OrderQueryRepository orderQueryRepository;

    private OrderTestApi orders;
    private TestOwner owner;
    private String variant;

    @BeforeEach
    void setUp() throws Exception {
        orders = new OrderTestApi(mockMvc);
        owner = createVerifiedOwner("order-list");
        variant = ProductTestApi.variantId(new ProductTestApi(mockMvc).createOk(owner.bearer(), "List Tee", "LIST-1"), 0);
        new InventoryTestApi(mockMvc).restockOk(owner.bearer(), variant, 50);
    }

    /** An order with its own phone, source and quantity, moved to {@code status}, created at {@code createdAt}. */
    private String order(String status, String source, String phone, int quantity, String createdAt) throws Exception {
        String json = OrderTestApi.orderJson(UUID.randomUUID().toString(), phone, "OUTSIDE_DHAKA",
                List.of(line(variant, quantity)), "").replace("MESSENGER", source);
        String id = OrderTestApi.id(orders.createOk(owner.bearer(), json));
        for (String step : OrderTestApi.pathTo(status)) {
            orders.actionOk(owner.bearer(), id, step);
        }
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(new ObjectId(id))),
                new Update().set("createdAt", java.util.Date.from(Instant.parse(createdAt))), "orders");
        return id;
    }

    private String list(String query) throws Exception {
        return mockMvc.perform(get("/api/v1/orders" + query).header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** The list searched with {@code q}, sent as a real request parameter (encoded once by MockMvc). */
    private String search(String q) throws Exception {
        return mockMvc.perform(get("/api/v1/orders").param("q", q).header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static List<String> ids(String page) {
        return JsonPath.read(page, "$.content[*].id");
    }

    // ==== List ===========================================================================

    @Nested
    class Listing {

        private String o1;
        private String o2;
        private String o3;
        private String o4;
        private String o5;

        @BeforeEach
        void fiveOrders() throws Exception {
            o1 = order("PENDING", "MESSENGER", "01712345678", 1, "2026-11-20T04:00:00Z");   // SD-1
            o2 = order("CONFIRMED", "FACEBOOK", "01812345678", 2, "2026-11-21T04:00:00Z");  // SD-2
            o3 = order("PACKED", "WHATSAPP", "01712345678", 3, "2026-11-22T04:00:00Z");     // SD-3
            o4 = order("CANCELLED", "PHONE", "01912345678", 1, "2026-11-23T17:59:59Z");     // 23:59:59 in Dhaka, 23 Nov
            o5 = order("SHIPPED", "MESSENGER", "01812345678", 2, "2026-11-23T18:00:00Z");   // 00:00 in Dhaka, 24 Nov
        }

        @Test
        void newestFirstByDefaultAndOldestFirstOnRequest() throws Exception {
            String page = list("");
            assertThat(ids(page)).containsExactly(o5, o4, o3, o2, o1);
            assertThat((Integer) JsonPath.read(page, "$.totalElements")).isEqualTo(5);
            assertThat(ids(list("?sort=createdAt,asc"))).containsExactly(o1, o2, o3, o4, o5);
        }

        @Test
        void rowHasTheListFields() throws Exception {
            mockMvc.perform(get("/api/v1/orders?q=SD-3").header("Authorization", owner.bearer()))
                    .andExpect(jsonPath("$.content[0].orderNumber").value("SD-3"))
                    .andExpect(jsonPath("$.content[0].status").value("PACKED"))
                    .andExpect(jsonPath("$.content[0].source").value("WHATSAPP"))
                    .andExpect(jsonPath("$.content[0].customerName").value("Rahim"))
                    .andExpect(jsonPath("$.content[0].customerPhone").value("01712345678"))
                    .andExpect(jsonPath("$.content[0].district").value("Dhaka"))
                    .andExpect(jsonPath("$.content[0].zone").value("OUTSIDE_DHAKA"))
                    .andExpect(jsonPath("$.content[0].itemCount").value(3))
                    .andExpect(jsonPath("$.content[0].total").value(15000))     // 3 × 1000 + 12000 outside Dhaka
                    .andExpect(jsonPath("$.content[0].codAmount").value(15000))
                    .andExpect(jsonPath("$.content[0].createdAt").value("2026-11-22T04:00:00Z"))
                    .andExpect(jsonPath("$.content[0].items").doesNotExist());
        }

        @Test
        void filtersByStatusSourceAndBoth() throws Exception {
            assertThat(ids(list("?status=PENDING&status=PACKED"))).containsExactly(o3, o1);
            assertThat(ids(list("?status=CANCELLED"))).containsExactly(o4);
            assertThat(ids(list("?source=MESSENGER"))).containsExactly(o5, o1);
            assertThat(ids(list("?status=CONFIRMED&source=FACEBOOK"))).containsExactly(o2);
            assertThat(ids(list("?status=CONFIRMED&source=MESSENGER"))).isEmpty();
        }

        @Test
        void dateRangeUsesDhakaDaysBothInclusive() throws Exception {
            // o5 was created at 00:00 on 24 Nov in Dhaka (18:00 UTC on the 23rd).
            assertThat(ids(list("?from=2026-11-24&to=2026-11-24"))).containsExactly(o5);
            // o4 at 23:59:59 on 23 Nov in Dhaka belongs to the 23rd.
            assertThat(ids(list("?from=2026-11-23&to=2026-11-23"))).containsExactly(o4);
            assertThat(ids(list("?to=2026-11-21"))).containsExactly(o2, o1);
            assertThat(ids(list("?from=2026-11-22"))).containsExactly(o5, o4, o3);
            assertThat(ids(list("?from=2026-11-21&to=2026-11-23&status=CANCELLED&status=CONFIRMED"))).containsExactly(o4, o2);
        }

        @Test
        void searchByOrderNumberInAnyFormOrByPhone() throws Exception {
            for (String q : List.of("SD-3", "sd-3", "SD3", "3", "#3", " 3 ")) {
                assertThat(ids(search(q)))
                        .as(q).containsExactly(o3);
            }
            assertThat(ids(list("?q=99"))).isEmpty();
            for (String q : List.of("01812345678", "+8801812345678", "01812-345678")) {
                assertThat(ids(search(q)))
                        .as(q).containsExactly(o5, o2);
            }
            assertThat(ids(list("?q=01712345678&status=PACKED"))).containsExactly(o3);
        }

        @Test
        void pagesDoNotOverlapAndCountEverything() throws Exception {
            List<String> seen = new ArrayList<>();
            for (int page = 0; page < 3; page++) {
                String body = list("?size=2&page=" + page);
                assertThat((Integer) JsonPath.read(body, "$.totalElements")).isEqualTo(5);
                assertThat((Integer) JsonPath.read(body, "$.totalPages")).isEqualTo(3);
                seen.addAll(ids(body));
            }
            assertThat(seen).containsExactly(o5, o4, o3, o2, o1);
            assertThat(ids(list("?size=2&page=3"))).isEmpty();
        }

        @Test
        void sameCreatedAtStillPagesStablyById() throws Exception {
            for (String id : List.of(o1, o2, o3, o4, o5)) {
                mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(new ObjectId(id))),
                        new Update().set("createdAt", java.util.Date.from(Instant.parse("2026-11-25T00:00:00Z"))), "orders");
            }
            List<String> seen = new ArrayList<>();
            for (int page = 0; page < 3; page++) {
                seen.addAll(ids(list("?size=2&page=" + page)));
            }
            // ObjectIds grow with creation time: newest first means o5 … o1 here too.
            assertThat(seen).containsExactly(o5, o4, o3, o2, o1);
        }

        @Test
        void badParametersAre400() throws Exception {
            Map<String, String> cases = Map.of(
                    "?status=SOLD", "status", "?source=TIKTOK", "source", "?sort=total,desc", "sort",
                    "?from=2026-13-01", "from", "?to=yesterday", "to", "?from=2026-11-25&to=2026-11-24", "from",
                    "?q=Rahim", "q", "?size=101", "size", "?page=-1", "page");
            for (Map.Entry<String, String> c : cases.entrySet()) {
                mockMvc.perform(get("/api/v1/orders" + c.getKey()).header("Authorization", owner.bearer()))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            }
        }

        @Test
        void everyListQueryUsesAShopIdIndexAndNeverSortsInMemory() {
            List<OrderQueryRepository.ListFilter> filters = List.of(
                    filter(null, null, null, null, null, null),
                    filter(EnumSet.of(OrderStatus.PENDING), null, null, null, null, null),
                    filter(EnumSet.of(OrderStatus.PENDING, OrderStatus.PACKED), null, null, null, null, null),
                    filter(null, OrderSource.MESSENGER, null, null, null, null),
                    filter(EnumSet.of(OrderStatus.CONFIRMED), OrderSource.FACEBOOK, null, null, null, null),
                    filter(null, null, Instant.parse("2026-11-21T18:00:00Z"), Instant.parse("2026-11-23T18:00:00Z"), null, null),
                    filter(EnumSet.of(OrderStatus.SHIPPED), null, Instant.parse("2026-11-20T00:00:00Z"), null, null, null),
                    filter(null, null, null, null, null, "01812345678"));
            for (OrderQueryRepository.ListFilter f : filters) {
                Plan plan = explain(f);
                assertThat(plan.stages()).as(f.toString()).doesNotContain("COLLSCAN", "SORT");
                assertThat(plan.indexes()).as(f.toString()).isNotEmpty().allMatch(name -> name.startsWith("shopId_"));
            }
            // By order number: the unique number index; at most one order, so a sort of it costs nothing.
            Plan byNumber = explain(filter(null, null, null, null, 3L, null));
            assertThat(byNumber.stages()).doesNotContain("COLLSCAN");
            assertThat(byNumber.indexes()).contains("shopId_orderNumber");
        }

        private OrderQueryRepository.ListFilter filter(java.util.Set<OrderStatus> statuses, OrderSource source,
                                                       Instant from, Instant to, Long number, String phone) {
            return new OrderQueryRepository.ListFilter(statuses, source, from, to, number, phone, false, 0, 20);
        }

        private record Plan(List<String> stages, List<String> indexes) {
        }

        /** Asks MongoDB how it runs the list query and collects every stage and index of the chosen plan. */
        private Plan explain(OrderQueryRepository.ListFilter f) {
            Query query = orderQueryRepository.listQuery(owner.shopId(), f);
            Document explained = mongoTemplate.getCollection("orders").find(query.getQueryObject())
                    .sort(query.getSortObject()).skip((int) query.getSkip()).limit(query.getLimit())
                    .explain();
            Object winning = explained.get("queryPlanner", Document.class).get("winningPlan");
            List<String> stages = new ArrayList<>();
            List<String> indexes = new ArrayList<>();
            collect(winning, stages, indexes);
            return new Plan(stages, indexes);
        }

        private void collect(Object node, List<String> stages, List<String> indexes) {
            if (node instanceof Document d) {
                if (d.get("stage") instanceof String stage) {
                    stages.add(stage);
                }
                if (d.get("indexName") instanceof String index) {
                    indexes.add(index);
                }
                d.values().forEach(v -> collect(v, stages, indexes));
            } else if (node instanceof List<?> list) {
                list.forEach(v -> collect(v, stages, indexes));
            }
        }
    }

    // ==== Bulk ===========================================================================

    @Nested
    class Bulk {

        private MockHttpServletRequestBuilder bulk(String json) {
            return post("/api/v1/orders/bulk-status").contentType(MediaType.APPLICATION_JSON).content(json)
                    .header("Authorization", owner.bearer());
        }

        private static String ids(String... ids) {
            return "\"" + String.join("\",\"", ids) + "\"";
        }

        @Test
        void eachOrderSucceedsOrFailsOnItsOwn() throws Exception {
            String okOrder = order("PENDING", "MESSENGER", "01712345678", 2, "2026-11-20T04:00:00Z");
            String tooBig = order("PENDING", "MESSENGER", "01712345678", 49, "2026-11-20T05:00:00Z");
            String cancelled = order("CANCELLED", "MESSENGER", "01712345678", 1, "2026-11-20T06:00:00Z");
            String alreadyConfirmed = order("CONFIRMED", "MESSENGER", "01712345678", 1, "2026-11-20T07:00:00Z");
            String unknown = new ObjectId().toHexString();
            // 50 on hand, 1 reserved by alreadyConfirmed. okOrder takes 2 → 47 left, tooBig wants 49.

            mockMvc.perform(bulk("{\"action\":\"CONFIRM\",\"note\":\"batch\",\"orderIds\":[%s]}"
                            .formatted(ids(okOrder, tooBig, cancelled, alreadyConfirmed, unknown, "not-an-id"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.action").value("CONFIRM"))
                    .andExpect(jsonPath("$.succeeded").value(2))
                    .andExpect(jsonPath("$.failed").value(4))
                    .andExpect(jsonPath("$.results[0].orderId").value(okOrder))
                    .andExpect(jsonPath("$.results[0].ok").value(true))
                    .andExpect(jsonPath("$.results[0].status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.results[0].orderNumber").value("SD-1"))
                    .andExpect(jsonPath("$.results[0].errorCode").doesNotExist())
                    .andExpect(jsonPath("$.results[1].ok").value(false))
                    .andExpect(jsonPath("$.results[1].errorCode").value("INSUFFICIENT_STOCK"))
                    .andExpect(jsonPath("$.results[2].errorCode").value("INVALID_STATUS_TRANSITION"))
                    .andExpect(jsonPath("$.results[3].ok").value(true))   // already confirmed: nothing to do
                    .andExpect(jsonPath("$.results[4].errorCode").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.results[5].errorCode").value("NOT_FOUND"));

            // The short order did not undo the good one, and was itself not changed.
            assertThat(orderStatus(okOrder)).isEqualTo("CONFIRMED");
            assertThat(orderStatus(tooBig)).isEqualTo("PENDING");
            assertThat(mongoTemplate.count(new Query(Criteria.where("type").is("RESERVE")), "stock_movements")).isEqualTo(2);
            Document confirmed = mongoTemplate.findById(new ObjectId(okOrder), Document.class, "orders");
            List<Document> history = confirmed.getList("statusHistory", Document.class);
            assertThat(history.get(history.size() - 1).getString("note")).isEqualTo("batch");
        }

        @Test
        void packShipAndCancelInBulk() throws Exception {
            String a = order("CONFIRMED", "MESSENGER", "01712345678", 1, "2026-11-20T04:00:00Z");
            String b = order("CONFIRMED", "MESSENGER", "01712345678", 1, "2026-11-20T05:00:00Z");
            String c = order("PENDING", "MESSENGER", "01712345678", 1, "2026-11-20T06:00:00Z");

            mockMvc.perform(bulk("{\"action\":\"PACK\",\"orderIds\":[%s]}".formatted(ids(a, b))))
                    .andExpect(jsonPath("$.succeeded").value(2));
            mockMvc.perform(bulk("{\"action\":\"SHIP\",\"courierName\":\"Pathao\",\"orderIds\":[%s]}".formatted(ids(a))))
                    .andExpect(jsonPath("$.results[0].status").value("SHIPPED"));
            assertThat(mongoTemplate.findById(new ObjectId(a), Document.class, "orders").get("shipping", Document.class)
                    .getString("courierName")).isEqualTo("Pathao");
            mockMvc.perform(bulk("{\"action\":\"CANCEL\",\"reason\":\"Fake orders\",\"orderIds\":[%s]}".formatted(ids(b, c))))
                    .andExpect(jsonPath("$.succeeded").value(2));
            assertThat(orderStatus(b)).isEqualTo("CANCELLED");
            assertThat(mongoTemplate.findById(new ObjectId(c), Document.class, "orders").getString("cancelReason"))
                    .isEqualTo("Fake orders");
        }

        @Test
        void invalidRequestsAre400AndChangeNothing() throws Exception {
            String a = order("PENDING", "MESSENGER", "01712345678", 1, "2026-11-20T04:00:00Z");
            List<String> fiftyOne = new ArrayList<>();
            for (int i = 0; i < 51; i++) {
                fiftyOne.add(new ObjectId().toHexString());
            }
            Map<String, String> cases = Map.of(
                    "{\"action\":\"CONFIRM\",\"orderIds\":[]}", "orderIds",
                    "{\"action\":\"CONFIRM\",\"orderIds\":[%s]}".formatted(ids(fiftyOne.toArray(String[]::new))), "orderIds",
                    "{\"action\":\"CONFIRM\",\"orderIds\":[%s]}".formatted(ids(a, a)), "orderIds",
                    "{\"action\":\"CANCEL\",\"orderIds\":[%s]}".formatted(ids(a)), "reason",
                    "{\"action\":\"RETURN\",\"reason\":\"x\",\"orderIds\":[%s]}".formatted(ids(a)), "action",
                    "{\"orderIds\":[%s]}".formatted(ids(a)), "action");
            for (Map.Entry<String, String> c : cases.entrySet()) {
                mockMvc.perform(bulk(c.getKey()))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.fieldErrors[0].field").value(c.getValue()));
            }
            mockMvc.perform(bulk("{\"action\":\"SELL\",\"orderIds\":[%s]}".formatted(ids(a))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
            assertThat(orderStatus(a)).isEqualTo("PENDING");
        }

        private String orderStatus(String id) {
            return mongoTemplate.findById(new ObjectId(id), Document.class, "orders").getString("status");
        }
    }

    // ==== Timeline =======================================================================

    @Test
    void timelineListsStatusChangesAndEditsInOrder() throws Exception {
        String id = OrderTestApi.id(orders.createOk(owner.bearer(), OrderTestApi.orderJson(line(variant, 1))));
        mockMvc.perform(OrderTestApi.updateRequest(id, 0, List.of(line(variant, 4)), ",\"note\":\"Gift wrap\"")
                .header("Authorization", owner.bearer())).andExpect(status().isOk());
        orders.actionOk(owner.bearer(), id, "confirm");
        orders.actionOk(owner.bearer(), id, "cancel");

        mockMvc.perform(get("/api/v1/orders/{id}/timeline", id).header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].type").value("STATUS"))
                .andExpect(jsonPath("$[0].to").value("PENDING"))
                .andExpect(jsonPath("$[0].from").doesNotExist())
                .andExpect(jsonPath("$[0].by.name").value("order-list Owner"))
                .andExpect(jsonPath("$[1].type").value("EDIT"))
                .andExpect(jsonPath("$[1].fields").value(org.hamcrest.Matchers.hasItems("items", "note")))
                .andExpect(jsonPath("$[1].summary").value("LIST-1 qty 1 → 4"))
                .andExpect(jsonPath("$[1].to").doesNotExist())
                .andExpect(jsonPath("$[2].from").value("PENDING"))
                .andExpect(jsonPath("$[2].to").value("CONFIRMED"))
                .andExpect(jsonPath("$[3].to").value("CANCELLED"))
                .andExpect(jsonPath("$[3].note").value("Customer changed mind"));

        mockMvc.perform(get("/api/v1/orders/{id}/timeline", new ObjectId().toHexString()).header("Authorization", owner.bearer()))
                .andExpect(status().isNotFound());
    }
}

package com.sellerdesk.order;

import static com.sellerdesk.order.OrderTestApi.line;
import static com.sellerdesk.order.OrderTestApi.orderJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sellerdesk.inventory.InventoryTestApi;
import com.sellerdesk.product.ProductTestApi;
import com.sellerdesk.testsupport.MongoIntegrationTestBase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;

/**
 * Orders against a real MongoDB through the real HTTP API: create with snapshots and server
 * totals, PENDING edits, the full status matrix with its stock and customer side effects, the
 * all-or-nothing confirm, and the concurrency guarantees (docs/modules/ORDER.md §11).
 */
class OrderIntegrationTest extends MongoIntegrationTestBase {

    private ProductTestApi products;
    private InventoryTestApi inventory;
    private OrderTestApi orders;
    private TestOwner owner;

    @BeforeEach
    void setUp() throws Exception {
        products = new ProductTestApi(mockMvc);
        inventory = new InventoryTestApi(mockMvc);
        orders = new OrderTestApi(mockMvc);
        owner = createVerifiedOwner("orders");
    }

    /** A new product with one variant (price ৳10 = 1000 poisha) and {@code stock} units; returns the variant id. */
    private String variantWithStock(int stock) throws Exception {
        String created = products.createOk(owner.bearer(), "Tee " + UUID.randomUUID(), "SKU-" + shortId());
        String variantId = ProductTestApi.variantId(created, 0);
        if (stock > 0) {
            inventory.restockOk(owner.bearer(), variantId, stock);
        }
        return variantId;
    }

    // ==== Create =========================================================================

    @Nested
    class Create {

        @Test
        void createsAPendingOrderWithSnapshotsServerTotalsAndANewCustomer() throws Exception {
            String variant = variantWithStock(5);
            String json = OrderTestApi.orderJson(UUID.randomUUID().toString(), "+880 1712-345678", "INSIDE_DHAKA",
                    List.of("{\"variantId\":\"%s\",\"quantity\":2}".formatted(variant)),
                    ",\"discount\":500,\"advancePaid\":1000,\"total\":1,\"subtotal\":1,\"codAmount\":1,\"note\":\"Call first\"");

            String body = orders.create(owner.bearer(), json)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.orderNumber").value("SD-1"))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.source").value("MESSENGER"))
                    .andExpect(jsonPath("$.items[0].unitPrice").value(1000))
                    .andExpect(jsonPath("$.items[0].lineTotal").value(2000))
                    .andExpect(jsonPath("$.items[0].available").value(5)) // owner has STOCK_VIEW; nothing reserved
                    .andExpect(jsonPath("$.subtotal").value(2000))
                    .andExpect(jsonPath("$.discount").value(500))
                    .andExpect(jsonPath("$.deliveryCharge").value(6000)) // shop default inside Dhaka
                    .andExpect(jsonPath("$.total").value(7500))         // client's "total":1 ignored
                    .andExpect(jsonPath("$.advancePaid").value(1000))
                    .andExpect(jsonPath("$.codAmount").value(6500))
                    .andExpect(jsonPath("$.customer.name").value("Rahim"))
                    .andExpect(jsonPath("$.customer.phone").value("01712345678"))
                    .andExpect(jsonPath("$.customer.address.district").value("Dhaka"))
                    .andExpect(jsonPath("$.statusHistory.length()").value(1))
                    .andExpect(jsonPath("$.statusHistory[0].to").value("PENDING"))
                    .andExpect(jsonPath("$.statusHistory[0].by.name").value("orders Owner"))
                    .andExpect(jsonPath("$.createdBy.id").value(owner.userId()))
                    .andExpect(jsonPath("$.allowedActions").value(org.hamcrest.Matchers.contains("CONFIRM", "CANCEL")))
                    .andExpect(jsonPath("$.note").value("Call first"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(stockOf(variant)).isEqualTo(List.of(5, 0, 5)); // not reserved at create
            Document customer = mongoTemplate.findOne(new Query(), Document.class, "customers");
            assertThat(customer.getString("phone")).isEqualTo("01712345678");
            assertThat(customer.get("stats", Document.class).getInteger("totalOrders")).isEqualTo(1);
            Document order = orderDocument(OrderTestApi.id(body));
            assertThat(order.get("shopId")).isEqualTo(new ObjectId(owner.shopId()));
            assertThat(order.get("customerId")).isEqualTo(customer.get("_id"));

            // The second order of the shop gets the next number and reuses the customer.
            orders.create(owner.bearer(), orderJson(line(variant, 1)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.orderNumber").value("SD-2"));
            assertThat(mongoTemplate.count(new Query(), "customers")).isOne();
        }

        @Test
        void outsideDhakaGetsTheOutsideChargeAndAnExplicitChargeWins() throws Exception {
            String variant = variantWithStock(0);
            orders.create(owner.bearer(), orderJson(UUID.randomUUID().toString(), "01812345678", "OUTSIDE_DHAKA",
                            List.of(line(variant, 1)), ""))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.deliveryCharge").value(12000));
            orders.create(owner.bearer(), orderJson(UUID.randomUUID().toString(), "01812345678", "OUTSIDE_DHAKA",
                            List.of(line(variant, 1)), ",\"deliveryCharge\":0"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.deliveryCharge").value(0))
                    .andExpect(jsonPath("$.total").value(1000));
        }

        @Test
        void insufficientStockDoesNotBlockCreateButShowsAvailable() throws Exception {
            String variant = variantWithStock(1);
            orders.create(owner.bearer(), orderJson(line(variant, 3)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.items[0].available").value(1));
        }

        @Test
        void archivedProductAndInactiveVariantCannotBeAdded() throws Exception {
            String created = products.createOk(owner.bearer(), "Old Tee", "OLD-1", "OLD-2");
            String productId = ProductTestApi.id(created);
            String v1 = ProductTestApi.variantId(created, 0);
            String v2 = ProductTestApi.variantId(created, 1);
            mockMvc.perform(patch("/api/v1/products/{id}/variants/{v}", productId, v2).header("Authorization", owner.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false,\"version\":0}"))
                    .andExpect(status().isOk());

            orders.create(owner.bearer(), orderJson(line(v1, 1), line(v2, 1)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VARIANT_NOT_ORDERABLE"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("items[1].variantId"));

            mockMvc.perform(patch("/api/v1/products/{id}/status", productId).header("Authorization", owner.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ARCHIVED\",\"version\":1}"))
                    .andExpect(status().isOk());
            orders.create(owner.bearer(), orderJson(line(v1, 1)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VARIANT_NOT_ORDERABLE"))
                    .andExpect(jsonPath("$.fieldErrors[0].message").value(org.hamcrest.Matchers.containsString("archived")));

            assertThat(mongoTemplate.count(new Query(), "orders")).isZero();
            assertThat(orderSeq()).isZero(); // refused before a number was taken
        }

        @Test
        void badInputIs400AndCreatesNothing() throws Exception {
            String v = variantWithStock(0);
            String rid = UUID.randomUUID().toString();
            Map<String, String> cases = Map.ofEntries(
                    Map.entry("items", orderJson(rid, "01712345678", "INSIDE_DHAKA", List.of(), "")),
                    Map.entry("items[0].quantity", orderJson(line(v, 0))),
                    Map.entry("items[1].variantId", orderJson(line(v, 1), line(v, 2))),
                    Map.entry("discount", orderJson(rid, "01712345678", "INSIDE_DHAKA", List.of(line(v, 1)), ",\"discount\":1001")),
                    Map.entry("advancePaid", orderJson(rid, "01712345678", "INSIDE_DHAKA", List.of(line(v, 1)), ",\"advancePaid\":7001")),
                    Map.entry("customer.phone", orderJson(rid, "12345", "INSIDE_DHAKA", List.of(line(v, 1)), "")),
                    Map.entry("requestId", orderJson("not-a-uuid", "01712345678", "INSIDE_DHAKA", List.of(line(v, 1)), "")),
                    Map.entry("customerId", orderJson(rid, "01712345678", "INSIDE_DHAKA", List.of(line(v, 1)),
                            ",\"customerId\":\"" + new ObjectId() + "\"")),
                    Map.entry("deliveryAddress", """
                            {"requestId":"%s","customer":{"name":"New","phone":"01912345678"},
                             "items":[%s],"source":"PHONE"}""".formatted(rid, line(v, 1))));
            for (Map.Entry<String, String> c : cases.entrySet()) {
                orders.create(owner.bearer(), c.getValue())
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                        .andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.hasItem(c.getKey())));
            }
            // Decimals and unknown enum values cannot even be read.
            orders.create(owner.bearer(), orderJson("{\"variantId\":\"%s\",\"quantity\":1,\"unitPrice\":10.5}".formatted(v)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
            orders.create(owner.bearer(), orderJson(line(v, 1)).replace("MESSENGER", "TIKTOK"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
            orders.create(owner.bearer(), orderJson(line(new ObjectId().toHexString(), 1)))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));

            assertThat(mongoTemplate.count(new Query(), "orders")).isZero();
            assertThat(mongoTemplate.count(new Query(), "customers")).isZero();
        }

        @Test
        void sameRequestIdTwiceCreatesOneOrderAndADifferentBodyIs409() throws Exception {
            String v = variantWithStock(0);
            String rid = UUID.randomUUID().toString();
            String json = orderJson(rid, "01712345678", "INSIDE_DHAKA", List.of(line(v, 1)), "");
            String first = orders.createOk(owner.bearer(), json);

            orders.create(owner.bearer(), json)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(OrderTestApi.id(first)));
            orders.create(owner.bearer(), orderJson(rid, "01712345678", "INSIDE_DHAKA", List.of(line(v, 2)), ""))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("REQUEST_ID_REUSED"));

            assertThat(mongoTemplate.count(new Query(), "orders")).isOne();
            assertThat(orderSeq()).isOne();
        }

        @Test
        void productChangesLaterNeverChangeTheOrderAndAUsedProductCannotBeDeleted() throws Exception {
            String created = products.createOk(owner.bearer(), "Snapshot Tee", "SNAP-1");
            String productId = ProductTestApi.id(created);
            String v = ProductTestApi.variantId(created, 0);
            String orderId = orders.createOrder(owner.bearer(), v, 1);

            mockMvc.perform(put("/api/v1/products/{id}", productId).header("Authorization", owner.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Renamed\",\"version\":0}"))
                    .andExpect(status().isOk());
            mockMvc.perform(patch("/api/v1/products/{id}/variants/{v}", productId, v).header("Authorization", owner.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"price\":99900,\"version\":1}"))
                    .andExpect(status().isOk());

            orders.getOrder(owner.bearer(), orderId)
                    .andExpect(jsonPath("$.items[0].productName").value("Snapshot Tee"))
                    .andExpect(jsonPath("$.items[0].unitPrice").value(1000));
            mockMvc.perform(delete("/api/v1/products/{id}", productId).header("Authorization", owner.bearer()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PRODUCT_IN_USE"));
        }
    }

    // ==== Edit ===========================================================================

    @Nested
    class Edit {

        @Test
        void pendingEditRecomputesTotalsKeepsStoredPricesAndLogsTheEdit() throws Exception {
            String created = products.createOk(owner.bearer(), "Edit Tee", "EDIT-1");
            String productId = ProductTestApi.id(created);
            String v = ProductTestApi.variantId(created, 0);
            String w = variantWithStock(0);
            String orderId = orders.createOrder(owner.bearer(), v, 1);
            // The variant price changes after the order was taken: the order keeps ৳10.
            mockMvc.perform(patch("/api/v1/products/{id}/variants/{v}", productId, v).header("Authorization", owner.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"price\":5000,\"version\":0}"))
                    .andExpect(status().isOk());

            mockMvc.perform(OrderTestApi.updateRequest(orderId, 0, List.of(line(v, 3), line(w, 1)), ",\"discount\":100")
                            .header("Authorization", owner.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].unitPrice").value(1000))
                    .andExpect(jsonPath("$.items[0].quantity").value(3))
                    .andExpect(jsonPath("$.items[1].unitPrice").value(1000))
                    .andExpect(jsonPath("$.subtotal").value(4000))
                    .andExpect(jsonPath("$.total").value(9900))
                    .andExpect(jsonPath("$.source").value("PHONE"))
                    .andExpect(jsonPath("$.customer.address.line").value("House 9"))
                    .andExpect(jsonPath("$.version").value(1));

            Document order = orderDocument(orderId);
            Document edit = order.getList("edits", Document.class).get(0);
            assertThat(edit.getList("fields", String.class)).contains("items", "discount", "deliveryAddress", "source");
            assertThat(edit.getString("summary")).contains("qty 1 → 3").contains("+SKU-");

            // A stale form is refused; after confirm nothing but address/note may change.
            mockMvc.perform(OrderTestApi.updateRequest(orderId, 0, List.of(line(v, 1)), "").header("Authorization", owner.bearer()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
            inventory.restockOk(owner.bearer(), v, 10);
            inventory.restockOk(owner.bearer(), w, 10);
            orders.actionOk(owner.bearer(), orderId, "confirm");
            mockMvc.perform(OrderTestApi.updateRequest(orderId, 2, List.of(line(v, 1)), "").header("Authorization", owner.bearer()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ORDER_NOT_EDITABLE"));
        }
    }

    // ==== Status matrix ==================================================================

    /** What each action leads to, written out here independently of the production table. */
    private static final Map<String, Map<String, String>> ALLOWED = Map.of(
            "PENDING", Map.of("confirm", "CONFIRMED", "cancel", "CANCELLED"),
            "CONFIRMED", Map.of("pack", "PACKED", "cancel", "CANCELLED"),
            "PACKED", Map.of("ship", "SHIPPED", "cancel", "CANCELLED"),
            "SHIPPED", Map.of("deliver", "DELIVERED", "return", "RETURNED"),
            "DELIVERED", Map.of(),
            "RETURNED", Map.of(),
            "CANCELLED", Map.of());

    private static final Map<String, String> TARGET = Map.of("confirm", "CONFIRMED", "pack", "PACKED",
            "ship", "SHIPPED", "deliver", "DELIVERED", "return", "RETURNED", "cancel", "CANCELLED");

    /** Stock of a variant with 10 units after an order for 2 reached each status. */
    private static final Map<String, List<Integer>> STOCK = Map.of(
            "PENDING", List.of(10, 0, 10), "CONFIRMED", List.of(10, 2, 8), "PACKED", List.of(10, 2, 8),
            "SHIPPED", List.of(8, 0, 8), "DELIVERED", List.of(8, 0, 8), "RETURNED", List.of(10, 0, 10),
            "CANCELLED", List.of(10, 0, 10));

    @Nested
    class StatusMatrix {

        @ParameterizedTest(name = "from {0}")
        @ValueSource(strings = {"PENDING", "CONFIRMED", "PACKED", "SHIPPED", "DELIVERED", "RETURNED", "CANCELLED"})
        void everyActionFromEveryStatus(String from) throws Exception {
            for (String action : TARGET.keySet()) {
                String variant = variantWithStock(10);
                String orderId = orders.createOrder(owner.bearer(), variant, 2);
                for (String step : OrderTestApi.pathTo(from)) {
                    orders.actionOk(owner.bearer(), orderId, step);
                }
                Document before = orderDocument(orderId);
                long movementsBefore = movementCount();
                String expected = ALLOWED.get(from).get(action);
                String label = action + " from " + from;

                if (expected != null) {
                    orders.action(owner.bearer(), orderId, action)
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.status").value(expected));
                    Document after = orderDocument(orderId);
                    List<Document> history = after.getList("statusHistory", Document.class);
                    assertThat(history.get(history.size() - 1).getString("from")).as(label).isEqualTo(from);
                    assertThat(history.get(history.size() - 1).getString("to")).as(label).isEqualTo(expected);
                    assertThat(stockOf(variant)).as(label).isEqualTo(STOCK.get(expected));
                } else if (TARGET.get(action).equals(from)) {
                    // Already there: 200, nothing written twice.
                    orders.action(owner.bearer(), orderId, action).andExpect(status().isOk());
                    assertThat(orderDocument(orderId)).as(label).isEqualTo(before);
                    assertThat(movementCount()).as(label).isEqualTo(movementsBefore);
                } else {
                    orders.action(owner.bearer(), orderId, action)
                            .andExpect(status().isConflict())
                            .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
                    assertThat(orderDocument(orderId)).as(label).isEqualTo(before);
                    assertThat(stockOf(variant)).as(label).isEqualTo(STOCK.get(from));
                    assertThat(movementCount()).as(label).isEqualTo(movementsBefore);
                }
            }
        }
    }

    // ==== Side effects ===================================================================

    @Nested
    class SideEffects {

        @Test
        void fullPipelineMovesStockAndCountsTheCustomer() throws Exception {
            String variant = variantWithStock(5);
            String body = orders.createOk(owner.bearer(), orderJson(line(variant, 2)));
            String orderId = OrderTestApi.id(body);
            long total = ((Number) JsonPath.read(body, "$.total")).longValue();

            orders.action(owner.bearer(), orderId, "confirm")
                    .andExpect(jsonPath("$.items[0].available").doesNotExist()); // reserved now: no live stock shown
            assertThat(stockOf(variant)).isEqualTo(List.of(5, 2, 3));
            orders.actionOk(owner.bearer(), orderId, "pack");
            orders.action(owner.bearer(), orderId, "ship")
                    .andExpect(jsonPath("$.shipping.courierName").value("Steadfast"))
                    .andExpect(jsonPath("$.shipping.trackingId").value("TRK-1"));
            assertThat(stockOf(variant)).isEqualTo(List.of(3, 0, 3));
            orders.action(owner.bearer(), orderId, "deliver")
                    .andExpect(jsonPath("$.status").value("DELIVERED"))
                    .andExpect(jsonPath("$.allowedActions").isEmpty())
                    .andExpect(jsonPath("$.statusHistory.length()").value(5));

            assertThat(movementTypes(orderId)).containsExactly("RESERVE", "DEDUCT");
            Document stats = mongoTemplate.findOne(new Query(), Document.class, "customers").get("stats", Document.class);
            assertThat(stats.getInteger("totalOrders")).isEqualTo(1);
            assertThat(stats.getInteger("deliveredOrders")).isEqualTo(1);
            assertThat(((Number) stats.get("totalSpent")).longValue()).isEqualTo(total);
        }

        @Test
        void cancelReleasesReservedStockFromConfirmedAndPacked() throws Exception {
            for (String from : List.of("CONFIRMED", "PACKED")) {
                String variant = variantWithStock(4);
                String orderId = orders.createOrder(owner.bearer(), variant, 3);
                for (String step : OrderTestApi.pathTo(from)) {
                    orders.actionOk(owner.bearer(), orderId, step);
                }
                assertThat(stockOf(variant)).isEqualTo(List.of(4, 3, 1));

                orders.action(owner.bearer(), orderId, "cancel")
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("CANCELLED"))
                        .andExpect(jsonPath("$.cancelReason").value("Customer changed mind"))
                        .andExpect(jsonPath("$.statusHistory[-1].note").value("Customer changed mind"));

                assertThat(stockOf(variant)).as(from).isEqualTo(List.of(4, 0, 4));
                assertThat(movementTypes(orderId)).as(from).containsExactly("RESERVE", "RELEASE");
            }
            Document stats = mongoTemplate.findOne(new Query(), Document.class, "customers").get("stats", Document.class);
            assertThat(stats.getInteger("cancelledOrders")).isEqualTo(2);
        }

        @Test
        void cancelNeedsAReason() throws Exception {
            String orderId = orders.createOrder(owner.bearer(), variantWithStock(1), 1);
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/orders/{id}/cancel", orderId).header("Authorization", owner.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("reason"));
        }

        @Test
        void returnRestocksOnlyTheItemsMarkedForRestock() throws Exception {
            String good = variantWithStock(5);
            String damaged = variantWithStock(5);
            String orderId = OrderTestApi.id(orders.createOk(owner.bearer(), orderJson(line(good, 2), line(damaged, 1))));
            for (String step : OrderTestApi.pathTo("SHIPPED")) {
                orders.actionOk(owner.bearer(), orderId, step);
            }

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/orders/{id}/return", orderId).header("Authorization", owner.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Refused\",\"items\":[{\"variantId\":\"%s\",\"restock\":false}]}".formatted(damaged)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("RETURNED"))
                    .andExpect(jsonPath("$.items[0].returnedRestocked").value(true))
                    .andExpect(jsonPath("$.items[1].returnedRestocked").value(false));

            assertThat(stockOf(good)).isEqualTo(List.of(5, 0, 5));
            assertThat(stockOf(damaged)).isEqualTo(List.of(4, 0, 4));
            Document stats = mongoTemplate.findOne(new Query(), Document.class, "customers").get("stats", Document.class);
            assertThat(stats.getInteger("returnedOrders")).isEqualTo(1);
        }

        @Test
        void confirmIsAllOrNothingAndReportsEveryShortItem() throws Exception {
            String enough = variantWithStock(5);
            String shortOne = variantWithStock(1);
            String shortTwo = variantWithStock(0);
            String orderId = OrderTestApi.id(orders.createOk(owner.bearer(),
                    orderJson(line(enough, 2), line(shortOne, 3), line(shortTwo, 1))));

            orders.action(owner.bearer(), orderId, "confirm")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                    .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("items[1].quantity"))
                    .andExpect(jsonPath("$.fieldErrors[0].message").value(org.hamcrest.Matchers.endsWith("requested 3, available 1")))
                    .andExpect(jsonPath("$.fieldErrors[1].field").value("items[2].quantity"));

            // Item 1 had enough, but its reservation was rolled back with the rest.
            assertThat(stockOf(enough)).isEqualTo(List.of(5, 0, 5));
            assertThat(movementCount("RESERVE")).isZero();
            orders.getOrder(owner.bearer(), orderId)
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.statusHistory.length()").value(1));
        }
    }

    // ==== Concurrency ====================================================================

    @Nested
    class Concurrency {

        @Test
        void ordersCompetingForTheLastUnitExactlyOneIsConfirmed() throws Exception {
            String variant = variantWithStock(1);
            List<String> orderIds = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                orderIds.add(orders.createOrder(owner.bearer(), variant, 1));
            }

            List<String> codes = runInParallel(orderIds.size(), i -> {
                String body = orders.action(owner.bearer(), orderIds.get(i), "confirm").andReturn().getResponse()
                        .getContentAsString();
                return body.contains("\"code\"") ? JsonPath.read(body, "$.code") : "OK";
            });

            assertThat(codes).filteredOn("OK"::equals).hasSize(1);
            assertThat(codes).filteredOn("INSUFFICIENT_STOCK"::equals).hasSize(9);
            assertThat(stockOf(variant)).isEqualTo(List.of(1, 1, 0));
            assertThat(movementCount("RESERVE")).isOne();
            long confirmed = mongoTemplate.count(new Query(Criteria.where("status").is("CONFIRMED")), "orders");
            assertThat(confirmed).isOne();
        }

        @Test
        void theSameOrderConfirmedFiveTimesAtOnceReservesOnce() throws Exception {
            String variant = variantWithStock(5);
            String orderId = orders.createOrder(owner.bearer(), variant, 2);

            List<Integer> statuses = runInParallel(5, i -> orders.action(owner.bearer(), orderId, "confirm")
                    .andReturn().getResponse().getStatus());

            assertThat(statuses).containsOnly(200);
            assertThat(stockOf(variant)).isEqualTo(List.of(5, 2, 3));
            assertThat(movementCount("RESERVE")).isOne();
            assertThat(orderDocument(orderId).getList("statusHistory", Document.class)).hasSize(2);
        }

        @Test
        void parallelCreatesGetDistinctNumbers() throws Exception {
            String variant = variantWithStock(0);
            List<String> numbers = runInParallel(10, i -> JsonPath.read(
                    orders.createOk(owner.bearer(), orderJson(line(variant, 1))), "$.orderNumber"));

            assertThat(new HashSet<>(numbers)).hasSize(10);
            assertThat(orderSeq()).isEqualTo(10);
            // All ten found the same new customer (find-or-create by phone is race-safe).
            assertThat(mongoTemplate.count(new Query(), "customers")).isOne();
            Document stats = mongoTemplate.findOne(new Query(), Document.class, "customers").get("stats", Document.class);
            assertThat(stats.getInteger("totalOrders")).isEqualTo(10);
        }
    }

    // ==== Helpers ========================================================================

    private List<Integer> stockOf(String variantId) {
        Document product = mongoTemplate.findOne(new Query(Criteria.where("variants.variantId").is(new ObjectId(variantId))),
                Document.class, "products");
        Document stock = product.getList("variants", Document.class).stream()
                .filter(v -> v.get("variantId").equals(new ObjectId(variantId)))
                .findFirst().orElseThrow().get("stock", Document.class);
        return List.of(stock.getInteger("onHand"), stock.getInteger("reserved"), stock.getInteger("available"));
    }

    private Document orderDocument(String orderId) {
        return mongoTemplate.findById(new ObjectId(orderId), Document.class, "orders");
    }

    private long orderSeq() {
        Document shop = mongoTemplate.findById(new ObjectId(owner.shopId()), Document.class, "shops");
        return ((Number) shop.get("orderSeq")).longValue();
    }

    private long movementCount() {
        return mongoTemplate.count(new Query(), "stock_movements");
    }

    private long movementCount(String type) {
        return mongoTemplate.count(new Query(Criteria.where("type").is(type)), "stock_movements");
    }

    private List<String> movementTypes(String orderId) {
        return mongoTemplate.find(new Query(Criteria.where("orderId").is(new ObjectId(orderId)))
                        .with(org.springframework.data.domain.Sort.by("createdAt", "_id")), Document.class, "stock_movements")
                .stream().map(d -> d.getString("type")).toList();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
    }

    private interface Task<T> {
        T run(int index) throws Exception;
    }

    /** Starts {@code count} tasks at the same moment (a latch) and returns their results. */
    private static <T> List<T> runInParallel(int count, Task<T> task) throws Exception {
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

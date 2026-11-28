package com.sellerdesk.inventory;

import static com.sellerdesk.inventory.InventoryTestApi.adjustJson;
import static com.sellerdesk.inventory.InventoryTestApi.adjustRequest;
import static com.sellerdesk.inventory.InventoryTestApi.restockJson;
import static com.sellerdesk.inventory.InventoryTestApi.restockRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sellerdesk.common.error.AppException;
import com.sellerdesk.common.security.AppUserDetails;
import com.sellerdesk.common.security.Role;
import com.sellerdesk.common.security.TenantContext;
import com.sellerdesk.common.transaction.TransactionRunner;
import com.sellerdesk.inventory.model.MovementType;
import com.sellerdesk.inventory.service.InventoryErrors;
import com.sellerdesk.inventory.service.InventoryService;
import com.sellerdesk.inventory.service.OrderRef;
import com.sellerdesk.inventory.service.StockLine;
import com.sellerdesk.product.ProductTestApi;
import com.sellerdesk.shop.ShopTestApi;
import com.sellerdesk.testsupport.MongoIntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * Inventory against a real MongoDB: atomic conditional updates, transactions, the movement log,
 * low-stock rules with the shop default, and the concurrency guarantees
 * (docs/modules/INVENTORY.md §8).
 */
class InventoryIntegrationTest extends MongoIntegrationTestBase {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private TransactionRunner transactionRunner;

    private ProductTestApi products;
    private InventoryTestApi inventory;
    private TestOwner owner;
    private String productId;
    private String variantId;

    @BeforeEach
    void setUp() throws Exception {
        products = new ProductTestApi(mockMvc);
        inventory = new InventoryTestApi(mockMvc);
        owner = createVerifiedOwner("inventory");
        String created = products.createOk(owner.bearer(), "Cotton Tee", "TEE-1");
        productId = ProductTestApi.id(created);
        variantId = ProductTestApi.variantId(created, 0);
    }

    // ==== Restock and adjustment ==========================================================

    @Nested
    class RestockAndAdjust {

        @Test
        void firstStockInIsInitialLaterOnesRestockWithBalancesAfter() throws Exception {
            inventory.restock(owner.bearer(), variantId, 10)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.movement.type").value("INITIAL"))
                    .andExpect(jsonPath("$.movement.onHandDelta").value(10))
                    .andExpect(jsonPath("$.movement.reservedDelta").value(0))
                    .andExpect(jsonPath("$.movement.onHandAfter").value(10))
                    .andExpect(jsonPath("$.movement.availableAfter").value(10))
                    .andExpect(jsonPath("$.movement.sku").value("TEE-1"))
                    .andExpect(jsonPath("$.movement.createdBy.id").value(owner.userId()))
                    .andExpect(jsonPath("$.movement.createdBy.name").value("inventory Owner"))
                    .andExpect(jsonPath("$.stock.onHand").value(10));

            inventory.restock(owner.bearer(), variantId, 5)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.movement.type").value("RESTOCK"))
                    .andExpect(jsonPath("$.movement.onHandAfter").value(15))
                    .andExpect(jsonPath("$.stock.available").value(15));

            assertThat(stock()).isEqualTo(List.of(15, 0, 15));
            Document movement = mongoTemplate.findOne(new Query(), Document.class, "stock_movements");
            assertThat(movement.get("shopId")).isEqualTo(new ObjectId(owner.shopId()));
            assertThat(movement.get("variantId")).isEqualTo(new ObjectId(variantId));
            assertThat(movement.get("productId")).isEqualTo(new ObjectId(productId));
            assertThat(movement).doesNotContainKey("orderId"); // partial index: field left out, not null
        }

        @Test
        void inventoryWritesDoNotChangeTheProductVersionAndProductEditsKeepStock() throws Exception {
            inventory.restockOk(owner.bearer(), variantId, 10);
            assertThat(ProductTestApi.version(products.fetch(owner.bearer(), productId))).isZero();

            // The product form loaded before the stock change still saves (version 0)...
            mockMvc.perform(put("/api/v1/products/{id}", productId).header("Authorization", owner.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Renamed\",\"version\":0}"))
                    .andExpect(status().isOk());
            mockMvc.perform(patch("/api/v1/products/{id}/variants/{v}", productId, variantId)
                            .header("Authorization", owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"price\":9900,\"version\":1}"))
                    .andExpect(status().isOk());
            // ...and never writes back old stock numbers.
            assertThat(stock()).isEqualTo(List.of(10, 0, 10));
        }

        @Test
        void decreaseCanOnlyRemoveUnreservedUnits() throws Exception {
            inventory.restockOk(owner.bearer(), variantId, 5);
            asOwner(() -> transactionRunner.run(() -> inventoryService.reserve(List.of(new StockLine(variantId, 3)), order(1))));
            assertThat(stock()).isEqualTo(List.of(5, 3, 2));
            long movementsBefore = movementCount();

            inventory.adjust(owner.bearer(), variantId, -3, "DAMAGED", null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                    .andExpect(jsonPath("$.message").value("Not enough stock for SKU TEE-1: requested 3, available 2"));
            assertThat(stock()).isEqualTo(List.of(5, 3, 2));
            assertThat(movementCount()).isEqualTo(movementsBefore);

            inventory.adjust(owner.bearer(), variantId, -2, "LOST", "gone")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.movement.type").value("ADJUSTMENT"))
                    .andExpect(jsonPath("$.movement.reasonCode").value("LOST"))
                    .andExpect(jsonPath("$.movement.note").value("gone"))
                    .andExpect(jsonPath("$.stock.available").value(0));
            assertThat(stock()).isEqualTo(List.of(3, 3, 0));
        }

        @Test
        void sameRequestIdTwiceAppliesOnceAndReturnsTheSameMovement() throws Exception {
            String requestId = UUID.randomUUID().toString();
            String first = body(restockRequest(variantId, restockJson(7, requestId)), 201);
            String second = body(restockRequest(variantId, restockJson(7, requestId.toUpperCase())), 201);

            assertThat((String) JsonPath.read(second, "$.movement.id")).isEqualTo(JsonPath.read(first, "$.movement.id"));
            assertThat(stock()).isEqualTo(List.of(7, 0, 7));
            assertThat(movementCount()).isOne();

            mockMvc.perform(restockRequest(variantId, restockJson(8, requestId)).header("Authorization", owner.bearer()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("REQUEST_ID_REUSED"));
            mockMvc.perform(adjustRequest(variantId, adjustJson(7, "FOUND", null, requestId))
                            .header("Authorization", owner.bearer()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("REQUEST_ID_REUSED"));
        }

        @Test
        void aFailedMovementInsertRollsBackTheStockChange() throws Exception {
            inventory.restockOk(owner.bearer(), variantId, 4);
            // Make MongoDB reject every new ADJUSTMENT document (a schema validator on the collection),
            // so the insert fails after the $inc already ran inside the transaction.
            mongoTemplate.executeCommand(new Document("collMod", "stock_movements")
                    .append("validator", new Document("type", new Document("$ne", "ADJUSTMENT"))));
            try {
                mockMvc.perform(adjustRequest(variantId, adjustJson(-1, "DAMAGED", null, UUID.randomUUID().toString()))
                                .header("Authorization", owner.bearer()))
                        .andExpect(status().isInternalServerError());
            } finally {
                mongoTemplate.executeCommand(new Document("collMod", "stock_movements").append("validator", new Document()));
            }
            assertThat(stock()).isEqualTo(List.of(4, 0, 4));
            assertThat(movementCount()).isOne();
        }

        @Test
        void aShortItemRollsBackTheWholeOrderReservation() throws Exception {
            String created = products.createOk(owner.bearer(), "Two Sizes", "TWO-S", "TWO-M");
            String small = ProductTestApi.variantId(created, 0);
            String medium = ProductTestApi.variantId(created, 1);
            inventory.restockOk(owner.bearer(), small, 5);
            inventory.restockOk(owner.bearer(), medium, 1);
            long movementsBefore = movementCount();

            assertThatThrownBy(() -> asOwner(() -> transactionRunner.run(() -> inventoryService.reserve(
                    List.of(new StockLine(small, 2), new StockLine(medium, 2)), order(7)))))
                    .isInstanceOf(AppException.class)
                    .extracting("code").isEqualTo(InventoryErrors.INSUFFICIENT_STOCK);

            assertThat(stockOf(small)).isEqualTo(List.of(5, 0, 5)); // the first item's reservation is undone
            assertThat(stockOf(medium)).isEqualTo(List.of(1, 0, 1));
            assertThat(movementCount()).isEqualTo(movementsBefore);
        }

        @Test
        void orderOperationsRefuseToRunWithoutATransaction() {
            assertThatThrownBy(() -> asOwner(() -> inventoryService.reserve(List.of(new StockLine(variantId, 1)), order(1))))
                    .isInstanceOf(IllegalTransactionStateException.class);
        }

        @Test
        void fullOrderLifecycleKeepsTheLogConsistentWithTheStock() throws Exception {
            inventory.restockOk(owner.bearer(), variantId, 10);
            OrderRef first = order(1);
            OrderRef second = order(2);
            List<StockLine> two = List.of(new StockLine(variantId, 2));
            asOwner(() -> transactionRunner.run(() -> inventoryService.reserve(two, first)));
            asOwner(() -> transactionRunner.run(() -> inventoryService.deduct(two, first)));
            asOwner(() -> transactionRunner.run(() -> inventoryService.restockReturn(List.of(new StockLine(variantId, 1)), first)));
            asOwner(() -> transactionRunner.run(() -> inventoryService.reserve(two, second)));
            asOwner(() -> transactionRunner.run(() -> inventoryService.release(two, second)));

            assertThat(stock()).isEqualTo(List.of(9, 0, 9));
            // The sum of the signed deltas is the stock.
            List<Document> all = mongoTemplate.find(new Query(), Document.class, "stock_movements");
            assertThat(all.stream().mapToInt(d -> d.getInteger("onHandDelta")).sum()).isEqualTo(9);
            assertThat(all.stream().mapToInt(d -> d.getInteger("reservedDelta")).sum()).isZero();

            mockMvc.perform(get("/api/v1/inventory/movements").param("orderId", first.orderId())
                            .header("Authorization", owner.bearer()))
                    .andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.content[0].type").value("RETURN"))
                    .andExpect(jsonPath("$.content[0].orderNumber").value("SD-1"))
                    .andExpect(jsonPath("$.content[2].type").value("RESERVE"));
        }

        @Test
        void productWithAMovementCannotBeDeleted() throws Exception {
            inventory.restockOk(owner.bearer(), variantId, 1);
            inventory.adjust(owner.bearer(), variantId, -1, "COUNT_CORRECTION", null).andExpect(status().isCreated());

            mockMvc.perform(delete("/api/v1/products/{id}", productId).header("Authorization", owner.bearer()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PRODUCT_IN_USE"));
        }

        @Test
        void inactiveVariantsCanStillBeRestockedAndReserved() throws Exception {
            mockMvc.perform(patch("/api/v1/products/{id}/variants/{v}", productId, variantId)
                            .header("Authorization", owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"active\":false,\"version\":0}"))
                    .andExpect(status().isOk());

            inventory.restockOk(owner.bearer(), variantId, 2);
            asOwner(() -> transactionRunner.run(() -> inventoryService.reserve(List.of(new StockLine(variantId, 1)), order(1))));
            assertThat(stock()).isEqualTo(List.of(2, 1, 1));
        }
    }

    // ==== Lists and low stock =============================================================

    @Nested
    class Lists {

        private String low;       // no own threshold (shop default 5), 3 available → LOW
        private String ownLow;    // own threshold 1, 3 available → IN_STOCK
        private String out;       // nothing → OUT

        @BeforeEach
        void threeVariants() throws Exception {
            String created = products.createOk(owner.bearer(), ProductTestApi.productJson("Alpha Shirt", null,
                    "ALPHA-L", "ALPHA-M", "ALPHA-S"));
            String alphaId = ProductTestApi.id(created);
            low = ProductTestApi.variantId(created, 0);
            ownLow = ProductTestApi.variantId(created, 1);
            out = ProductTestApi.variantId(created, 2);
            mockMvc.perform(patch("/api/v1/products/{id}/variants/{v}", alphaId, ownLow)
                            .header("Authorization", owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"lowStockThreshold\":1,\"version\":0}"))
                    .andExpect(status().isOk());
            inventory.restockOk(owner.bearer(), low, 3);
            inventory.restockOk(owner.bearer(), ownLow, 3);
            inventory.restockOk(owner.bearer(), variantId, 50); // Cotton Tee: plenty
        }

        @Test
        void listShowsEveryActiveVariantWithEffectiveThresholdAndStatus() throws Exception {
            mockMvc.perform(get("/api/v1/inventory").header("Authorization", owner.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(4))
                    // sorted by product name, then SKU
                    .andExpect(jsonPath("$.content[0].sku").value("ALPHA-L"))
                    .andExpect(jsonPath("$.content[0].lowStockThreshold").value(5))
                    .andExpect(jsonPath("$.content[0].thresholdSource").value("SHOP"))
                    .andExpect(jsonPath("$.content[0].stockStatus").value("LOW"))
                    .andExpect(jsonPath("$.content[1].sku").value("ALPHA-M"))
                    .andExpect(jsonPath("$.content[1].lowStockThreshold").value(1))
                    .andExpect(jsonPath("$.content[1].thresholdSource").value("VARIANT"))
                    .andExpect(jsonPath("$.content[1].stockStatus").value("IN_STOCK"))
                    .andExpect(jsonPath("$.content[2].sku").value("ALPHA-S"))
                    .andExpect(jsonPath("$.content[2].stockStatus").value("OUT"))
                    .andExpect(jsonPath("$.content[3].sku").value("TEE-1"))
                    .andExpect(jsonPath("$.content[3].productName").value("Cotton Tee"))
                    .andExpect(jsonPath("$.content[3].onHand").value(50));
        }

        @Test
        void statusFiltersSearchSortAndPaging() throws Exception {
            expectSkus("/api/v1/inventory?stockStatus=LOW", "ALPHA-L");
            expectSkus("/api/v1/inventory?stockStatus=OUT", "ALPHA-S");
            expectSkus("/api/v1/inventory?stockStatus=LOW_OR_OUT", "ALPHA-L", "ALPHA-S");
            expectSkus("/api/v1/inventory?q=alpha-m", "ALPHA-M");          // exact SKU: only that variant
            expectSkus("/api/v1/inventory?q=Alp", "ALPHA-L", "ALPHA-M", "ALPHA-S"); // name prefix: all variants
            expectSkus("/api/v1/inventory?sort=available,asc", "ALPHA-S", "ALPHA-L", "ALPHA-M", "TEE-1");
            mockMvc.perform(get("/api/v1/inventory?size=2&page=1").header("Authorization", owner.bearer()))
                    .andExpect(jsonPath("$.content.length()").value(2))
                    .andExpect(jsonPath("$.content[0].sku").value("ALPHA-S"))
                    .andExpect(jsonPath("$.totalElements").value(4))
                    .andExpect(jsonPath("$.totalPages").value(2));
        }

        @Test
        void lowStockListIsLowAndOutLowestFirst() throws Exception {
            expectSkus("/api/v1/inventory/low-stock", "ALPHA-S", "ALPHA-L");
        }

        @Test
        void changingTheShopDefaultChangesTheListAtOnce() throws Exception {
            String json = ShopTestApi.updateJson("Inventory Shop", "01712345678", "SD")
                    .replace("\"defaultLowStockThreshold\":3", "\"defaultLowStockThreshold\":2");
            mockMvc.perform(ShopTestApi.update(json).header("Authorization", owner.bearer())).andExpect(status().isOk());

            // 3 available > 2: ALPHA-L is no longer low. Only the sold-out variant is left.
            expectSkus("/api/v1/inventory/low-stock", "ALPHA-S");
        }

        @Test
        void resetToShopDefaultThreshold() throws Exception {
            String alphaId = productIdOf(ownLow);
            mockMvc.perform(patch("/api/v1/products/{id}/variants/{v}", alphaId, ownLow)
                            .header("Authorization", owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"lowStockThreshold\":4,\"useShopDefaultThreshold\":true,\"version\":1}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("useShopDefaultThreshold"));
            mockMvc.perform(patch("/api/v1/products/{id}/variants/{v}", alphaId, ownLow)
                            .header("Authorization", owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"useShopDefaultThreshold\":true,\"version\":1}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.variants[1].lowStockThreshold").doesNotExist());

            mockMvc.perform(get("/api/v1/inventory/variants/{v}", ownLow).header("Authorization", owner.bearer()))
                    .andExpect(jsonPath("$.row.lowStockThreshold").value(5))
                    .andExpect(jsonPath("$.row.thresholdSource").value("SHOP"))
                    .andExpect(jsonPath("$.row.stockStatus").value("LOW"));
            assertThat(mongoTemplate.findById(new ObjectId(alphaId), Document.class, "products")
                    .getList("variants", Document.class).get(1)).doesNotContainKey("lowStockThreshold");
        }

        @Test
        void inactiveVariantsAndArchivedProductsOnlyWithIncludeInactive() throws Exception {
            String alphaId = productIdOf(low);
            mockMvc.perform(patch("/api/v1/products/{id}/variants/{v}", alphaId, low)
                            .header("Authorization", owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"active\":false,\"version\":1}"))
                    .andExpect(status().isOk());
            mockMvc.perform(patch("/api/v1/products/{id}/status", productId)
                            .header("Authorization", owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"ARCHIVED\",\"version\":0}"))
                    .andExpect(status().isOk());

            expectSkus("/api/v1/inventory", "ALPHA-M", "ALPHA-S");
            expectSkus("/api/v1/inventory/low-stock", "ALPHA-S");
            mockMvc.perform(get("/api/v1/inventory?includeInactive=true").header("Authorization", owner.bearer()))
                    .andExpect(jsonPath("$.totalElements").value(4))
                    .andExpect(jsonPath("$.content[0].active").value(false))
                    .andExpect(jsonPath("$.content[3].productStatus").value("ARCHIVED"));
        }

        @Test
        void variantDetailHasRowAndNewestMovements() throws Exception {
            inventory.restockOk(owner.bearer(), low, 2);
            mockMvc.perform(get("/api/v1/inventory/variants/{v}", low).header("Authorization", owner.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.row.sku").value("ALPHA-L"))
                    .andExpect(jsonPath("$.row.onHand").value(5))
                    .andExpect(jsonPath("$.recentMovements.length()").value(2))
                    .andExpect(jsonPath("$.recentMovements[0].type").value("RESTOCK"))
                    .andExpect(jsonPath("$.recentMovements[1].type").value("INITIAL"));
        }

        @Test
        void movementFiltersAndPaging() throws Exception {
            inventory.adjust(owner.bearer(), low, -1, "DAMAGED", null).andExpect(status().isCreated());

            String bearer = owner.bearer();
            mockMvc.perform(get("/api/v1/inventory/movements").header("Authorization", bearer))
                    .andExpect(jsonPath("$.totalElements").value(4))
                    .andExpect(jsonPath("$.content[0].type").value("ADJUSTMENT")); // newest first
            mockMvc.perform(get("/api/v1/inventory/movements?variantId=" + low).header("Authorization", bearer))
                    .andExpect(jsonPath("$.totalElements").value(2));
            mockMvc.perform(get("/api/v1/inventory/movements?productId=" + productId).header("Authorization", bearer))
                    .andExpect(jsonPath("$.totalElements").value(1));
            mockMvc.perform(get("/api/v1/inventory/movements?type=INITIAL").header("Authorization", bearer))
                    .andExpect(jsonPath("$.totalElements").value(3));
            mockMvc.perform(get("/api/v1/inventory/movements?reasonCode=DAMAGED").header("Authorization", bearer))
                    .andExpect(jsonPath("$.totalElements").value(1));
            mockMvc.perform(get("/api/v1/inventory/movements?from=2000-01-01&to=2000-01-02").header("Authorization", bearer))
                    .andExpect(jsonPath("$.totalElements").value(0));
            mockMvc.perform(get("/api/v1/inventory/movements?from=2000-01-01").header("Authorization", bearer))
                    .andExpect(jsonPath("$.totalElements").value(4));
            mockMvc.perform(get("/api/v1/inventory/movements?size=3&page=1").header("Authorization", bearer))
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.totalPages").value(2));
        }

        private String productIdOf(String someVariantId) {
            Document product = mongoTemplate.findOne(new Query(Criteria.where("variants.variantId").is(new ObjectId(someVariantId))),
                    Document.class, "products");
            return product.getObjectId("_id").toHexString();
        }

        private void expectSkus(String url, String... skus) throws Exception {
            String json = body(get(url), 200);
            List<String> actual = JsonPath.read(json, "$.content[*].sku");
            assertThat(actual).as(url).containsExactly(skus);
        }
    }

    // ==== Concurrency =====================================================================

    @Nested
    class Concurrency {

        @Test
        void tenParallelReservationsOfTheLastUnitExactlyOneSucceeds() throws Exception {
            inventory.restockOk(owner.bearer(), variantId, 1);

            List<String> results = runInParallel(10, i -> asOwner(() -> {
                try {
                    transactionRunner.run(() -> inventoryService.reserve(List.of(new StockLine(variantId, 1)), order(i)));
                    return "OK";
                } catch (AppException e) {
                    return e.getCode();
                }
            }));

            assertThat(results).filteredOn("OK"::equals).hasSize(1);
            assertThat(results).filteredOn(InventoryErrors.INSUFFICIENT_STOCK::equals).hasSize(9);
            assertThat(stock()).isEqualTo(List.of(1, 1, 0));
            assertThat(movementCount(MovementType.RESERVE)).isOne();
        }

        @Test
        void parallelDecreasesNeverGoBelowZero() throws Exception {
            inventory.restockOk(owner.bearer(), variantId, 3);

            List<Integer> statuses = runInParallel(5, i -> mockMvc.perform(adjustRequest(variantId,
                            adjustJson(-1, "LOST", null, UUID.randomUUID().toString()))
                            .header("Authorization", owner.bearer()))
                    .andReturn().getResponse().getStatus());

            assertThat(statuses).filteredOn(s -> s == 201).hasSize(3);
            assertThat(statuses).filteredOn(s -> s == 409).hasSize(2);
            assertThat(stock()).isEqualTo(List.of(0, 0, 0));
        }

        @Test
        void parallelRestocksAllCountWithExactlyOneInitial() throws Exception {
            List<Integer> statuses = runInParallel(8, i -> mockMvc.perform(restockRequest(variantId,
                            restockJson(1, UUID.randomUUID().toString())).header("Authorization", owner.bearer()))
                    .andReturn().getResponse().getStatus());

            assertThat(statuses).containsOnly(201);
            assertThat(stock()).isEqualTo(List.of(8, 0, 8));
            List<Integer> after = mongoTemplate.find(new Query(), Document.class, "stock_movements").stream()
                    .map(d -> d.getInteger("onHandAfter")).sorted().toList();
            assertThat(after).isEqualTo(IntStream.rangeClosed(1, 8).boxed().toList());
            assertThat(movementCount(MovementType.INITIAL)).isOne();
            assertThat(movementCount(MovementType.RESTOCK)).isEqualTo(7);
        }

        @Test
        void parallelRepeatsOfOneRequestIdApplyOnce() throws Exception {
            String requestId = UUID.randomUUID().toString();
            List<String> movementIds = runInParallel(5, i -> {
                String json = mockMvc.perform(restockRequest(variantId, restockJson(4, requestId))
                                .header("Authorization", owner.bearer()))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();
                return JsonPath.read(json, "$.movement.id");
            });

            assertThat(movementIds).hasSize(5).containsOnly(movementIds.get(0));
            assertThat(stock()).isEqualTo(List.of(4, 0, 4));
            assertThat(movementCount()).isOne();
        }
    }

    // ==== Helpers =========================================================================

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

    /**
     * Runs service code as the owner on the current thread: what the JWT filter does for a
     * request (security context + tenant), then cleans both up.
     */
    private <T> T asOwner(Callable<T> work) throws Exception {
        AppUserDetails details = new AppUserDetails(owner.userId(), owner.shopId(), owner.email(), "x",
                Role.SHOP_OWNER, Role.SHOP_OWNER.defaultPermissions(), true, 0);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
        TenantContext.setShopId(owner.shopId());
        try {
            return work.call();
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private static OrderRef order(int number) {
        return new OrderRef(new ObjectId().toHexString(), "SD-" + number);
    }

    private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                        int expectedStatus) throws Exception {
        return mockMvc.perform(request.header("Authorization", owner.bearer()))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    private List<Integer> stock() {
        return stockOf(variantId);
    }

    /** [onHand, reserved, available] straight from the database. */
    private List<Integer> stockOf(String someVariantId) {
        Document product = mongoTemplate.findOne(
                new Query(Criteria.where("variants.variantId").is(new ObjectId(someVariantId))), Document.class, "products");
        Document variant = product.getList("variants", Document.class).stream()
                .filter(v -> v.getObjectId("variantId").toHexString().equals(someVariantId))
                .findFirst().orElseThrow();
        Document s = variant.get("stock", Document.class);
        return List.of(s.getInteger("onHand"), s.getInteger("reserved"), s.getInteger("available"));
    }

    private long movementCount() {
        return mongoTemplate.count(new Query(), "stock_movements");
    }

    private long movementCount(MovementType type) {
        return mongoTemplate.count(new Query(Criteria.where("type").is(type.name())), "stock_movements");
    }
}

package com.sellerdesk.product;

import static com.sellerdesk.product.ProductTestApi.id;
import static com.sellerdesk.product.ProductTestApi.imageId;
import static com.sellerdesk.product.ProductTestApi.productJson;
import static com.sellerdesk.product.ProductTestApi.variantId;
import static com.sellerdesk.product.ProductTestApi.version;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sellerdesk.common.storage.ImageCleanupReason;
import com.sellerdesk.common.storage.ImageCleanupTask;
import com.sellerdesk.common.storage.ImageCleanupTasks;
import com.sellerdesk.testsupport.MongoIntegrationTestBase;
import com.sellerdesk.testsupport.TestImages;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
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
 * Main product flows against a real MongoDB (indexes, atomic updates, cache, images with
 * {@code FakeImageStorage}). docs/modules/PRODUCT.md §10.
 */
class ProductIntegrationTest extends MongoIntegrationTestBase {

    @Autowired
    private ImageCleanupTasks cleanupTasks;

    private ProductTestApi api;
    private TestOwner owner;

    @BeforeEach
    void setUp() throws Exception {
        api = new ProductTestApi(mockMvc);
        owner = createVerifiedOwner("products");
    }

    // ==== Create, SKU uniqueness ========================================================

    @Nested
    class CreateAndSku {

        @Test
        void createReturns201WithNormalizedSkuZeroStockAndVersion0() throws Exception {
            api.create(owner.bearer(), """
                            {"name":"  Cotton Tee ","category":" Men's   Wear ","variants":[
                              {"sku":" tee-red-m ","attributes":{"Color":"Red","Size":"M"},"price":45050,"costPrice":30000}]}""")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNotEmpty())
                    .andExpect(jsonPath("$.name").value("Cotton Tee"))
                    .andExpect(jsonPath("$.category").value("Men's Wear"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.version").value(0))
                    .andExpect(jsonPath("$.variants[0].sku").value("TEE-RED-M"))
                    .andExpect(jsonPath("$.variants[0].name").value("Red / M"))
                    .andExpect(jsonPath("$.variants[0].price").value(45050))
                    .andExpect(jsonPath("$.variants[0].costPrice").value(30000))
                    .andExpect(jsonPath("$.variants[0].stock.onHand").value(0))
                    .andExpect(jsonPath("$.variants[0].stock.available").value(0));

            Document stored = mongoTemplate.findOne(new Query(), Document.class, "products");
            assertThat(stored.get("shopId")).isEqualTo(new ObjectId(owner.shopId()));
            assertThat(stored.get("minPrice")).isEqualTo(45050L);
            assertThat(stored.getString("nameLower")).isEqualTo("cotton tee");
            assertThat(stored.getString("categoryKey")).isEqualTo("men's wear");
        }

        @Test
        void sameSkuInTwoProductsOfOneShopIs409WithField() throws Exception {
            api.createOk(owner.bearer(), "First", "SKU-1");

            api.create(owner.bearer(), productJson("Second", null, "SKU-2", "sku-1"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SKU_ALREADY_EXISTS"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("variants[1].sku"));
        }

        @Test
        void sameSkuTwiceInOneRequestIs400() throws Exception {
            api.create(owner.bearer(), productJson("Dup", null, "A-1", "a-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("variants[1].sku"));
        }

        @Test
        void beanValidationRejectsBadInput() throws Exception {
            api.create(owner.bearer(), """
                            {"name":"","variants":[{"sku":"bad sku!","price":-1}]}""")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'name')]").exists())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'variants[0].sku')]").exists())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'variants[0].price')]").exists());
            api.create(owner.bearer(), """
                            {"name":"No variants","variants":[]}""")
                    .andExpect(status().isBadRequest());
        }

        @Test
        void duplicateAttributesIs409() throws Exception {
            api.create(owner.bearer(), """
                            {"name":"Tee","variants":[
                              {"sku":"A","attributes":{"Size":"M"},"price":1},
                              {"sku":"B","attributes":{"size":"m"},"price":1}]}""")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATE_VARIANT_ATTRIBUTES"));
        }

        /** The unique index is the real guarantee: of parallel creates with one SKU exactly one wins. */
        @Test
        void parallelCreatesWithOneSkuGiveExactlyOne201() throws Exception {
            List<Integer> statuses = runInParallel(6, i -> () -> api.create(owner.bearer(),
                    productJson("Race " + i, null, "RACE-1")).andReturn().getResponse().getStatus());

            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
            assertThat(statuses).filteredOn(s -> s == 409).hasSize(5);
            assertThat(mongoTemplate.count(new Query(), "products")).isOne();
        }

        @Test
        void uniqueSkuIndexPerShopExists() {
            assertThat(mongoTemplate.indexOps("products").getIndexInfo())
                    .anySatisfy(index -> {
                        assertThat(index.getName()).isEqualTo("shopId_sku");
                        assertThat(index.isUnique()).isTrue();
                    });
        }

        @Test
        void sameSkuInTwoShopsIsFine() throws Exception {
            TestOwner other = createVerifiedOwner("products-other");
            api.createOk(owner.bearer(), "Mine", "SHARED-1");
            api.createOk(other.bearer(), "Theirs", "SHARED-1");
        }
    }

    // ==== Edits: version, lost update, variants, status =================================

    @Nested
    class Edits {

        @Test
        void editsNeverOverwriteStockWrittenByInventory() throws Exception {
            String created = api.createOk(owner.bearer(), "Tee", "TEE-S");
            String productId = id(created);
            setStock(productId, 7, 2);

            long v = version(created);
            v = versionOf(putProduct(productId, "Tee renamed", v).andExpect(status().isOk()).andReturn());
            v = versionOf(addVariant(productId, "TEE-L", "L", v).andExpect(status().isCreated()).andReturn());
            v = versionOf(patchVariant(productId, variantId(created, 0), "{\"price\":9900,\"version\":" + v + "}")
                    .andExpect(status().isOk()).andReturn());
            v = versionOf(patchStatus(productId, "ARCHIVED", v).andExpect(status().isOk()).andReturn());
            api.uploadOk(owner.bearer(), productId);
            String two = api.uploadOk(owner.bearer(), productId);
            reorder(productId, List.of(imageId(two, 1), imageId(two, 0)), version(two)).andExpect(status().isOk());

            Document stock = stockOf(productId, 0);
            assertThat(stock.getInteger("onHand")).isEqualTo(7);
            assertThat(stock.getInteger("reserved")).isEqualTo(2);
            assertThat(stock.getInteger("available")).isEqualTo(5);
        }

        @Test
        void staleVersionIs409OnEveryVersionedEndpoint() throws Exception {
            String created = api.createOk(owner.bearer(), "Tee", "TEE-S");
            String productId = id(created);
            putProduct(productId, "New name", 0).andExpect(status().isOk()); // now version 1

            putProduct(productId, "Stale", 0).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
            patchStatus(productId, "ARCHIVED", 0).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
            addVariant(productId, "TEE-L", "L", 0).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
            patchVariant(productId, variantId(created, 0), "{\"price\":1,\"version\":0}")
                    .andExpect(status().isConflict());
            reorder(productId, List.of(), 0).andExpect(status().isConflict());

            assertThat(JsonPath.<String>read(api.fetch(owner.bearer(), productId), "$.name")).isEqualTo("New name");
        }

        @Test
        void addVariantRules() throws Exception {
            String created = api.createOk(owner.bearer(), "Tee", "TEE-S");
            String productId = id(created);
            api.createOk(owner.bearer(), "Other", "OTHER-1");

            addVariant(productId, "tee-s", "XL", 0).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SKU_ALREADY_EXISTS"));
            addVariant(productId, "OTHER-1", "XL", 0).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SKU_ALREADY_EXISTS"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("sku"));
            addVariant(productId, "TEE-XL", "S0", 0).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATE_VARIANT_ATTRIBUTES"));
            addVariant(productId, "tee-xl", "XL", 0).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.variants.length()").value(2))
                    .andExpect(jsonPath("$.variants[1].sku").value("TEE-XL"))
                    .andExpect(jsonPath("$.variants[1].stock.onHand").value(0))
                    .andExpect(jsonPath("$.version").value(1));
        }

        @Test
        void variantLimitIs100() throws Exception {
            String[] skus = new String[100];
            for (int i = 0; i < 100; i++) {
                skus[i] = "LIM-" + i;
            }
            String productId = id(api.createOk(owner.bearer(), "Many", skus));
            addVariant(productId, "LIM-100", "XXL", 0).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VARIANT_LIMIT_REACHED"));
        }

        @Test
        void deactivatingVariantUpdatesPriceRangeAndSkuCannotChange() throws Exception {
            String created = api.createOk(owner.bearer(), "Tee", "TEE-S", "TEE-M");  // 1000, 2000
            String productId = id(created);

            patchVariant(productId, variantId(created, 1), "{\"active\":false,\"sku\":\"HACK\",\"version\":0}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.variants[1].active").value(false))
                    .andExpect(jsonPath("$.variants[1].sku").value("TEE-M"));

            Document stored = mongoTemplate.findById(new ObjectId(productId), Document.class, "products");
            assertThat(stored.get("minPrice")).isEqualTo(1000L);
            assertThat(stored.get("maxPrice")).isEqualTo(1000L);
        }

        @Test
        void unknownVariantIs404() throws Exception {
            String productId = id(api.createOk(owner.bearer(), "Tee", "TEE-S"));
            patchVariant(productId, new ObjectId().toHexString(), "{\"price\":1,\"version\":0}")
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        void archiveAndUnarchive() throws Exception {
            String productId = id(api.createOk(owner.bearer(), "Tee", "TEE-S"));

            patchStatus(productId, "ARCHIVED", 0).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
            // Archived products stay editable.
            putProduct(productId, "Tee fixed", 1).andExpect(status().isOk());
            patchStatus(productId, "ACTIVE", 2).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
            patchStatus(productId, "DELETED", 3).andExpect(status().isBadRequest());
        }

        @Test
        void deleteUnusedProductAndRefuseStockedOne() throws Exception {
            String unused = id(api.createOk(owner.bearer(), "Unused", "U-1"));
            String stocked = id(api.createOk(owner.bearer(), "Stocked", "S-1"));
            setStock(stocked, 3, 0);

            mockMvc.perform(delete("/api/v1/products/{id}", unused).header("Authorization", owner.bearer()))
                    .andExpect(status().isNoContent());
            mockMvc.perform(get("/api/v1/products/{id}", unused).header("Authorization", owner.bearer()))
                    .andExpect(status().isNotFound());
            mockMvc.perform(delete("/api/v1/products/{id}", stocked).header("Authorization", owner.bearer()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PRODUCT_IN_USE"));
            assertThat(mongoTemplate.findById(new ObjectId(stocked), Document.class, "products")).isNotNull();
        }
    }

    // ==== List / search =================================================================

    @Nested
    class Search {

        @BeforeEach
        void catalog() throws Exception {
            api.createOk(owner.bearer(), productJson("Blue Shirt", "Shirts", "BS-1"));        // 1000
            api.createOk(owner.bearer(), productJson("Black Pants", "Pants", "BP-1", "BP-2")); // 1000..2000
            api.createOk(owner.bearer(), productJson("Red Shirt", "shirts", "RS-1", "RS-2", "RS-3"));
            String archived = api.createOk(owner.bearer(), productJson("Blazer", "Jackets", "BZ-1"));
            patchStatus(id(archived), "ARCHIVED", 0).andExpect(status().isOk());
        }

        @Test
        void namePrefixIsCaseInsensitive() throws Exception {
            assertThat(names(list("q=bl"))).containsExactlyInAnyOrder("Blue Shirt", "Black Pants");
        }

        @Test
        void exactSkuMatches() throws Exception {
            assertThat(names(list("q=rs-2"))).containsExactly("Red Shirt");
            assertThat(names(list("q=RS"))).isEmpty(); // no partial SKU match, and no name starts with "rs"
        }

        @Test
        void regexCharactersAreLiteral() throws Exception {
            assertThat(names(list("q=.*"))).isEmpty();
        }

        @Test
        void categoryIsCaseInsensitive() throws Exception {
            assertThat(names(list("category=SHIRTS"))).containsExactlyInAnyOrder("Blue Shirt", "Red Shirt");
        }

        @Test
        void statusFilter() throws Exception {
            assertThat(names(list(""))).doesNotContain("Blazer").hasSize(3);
            assertThat(names(list("status=ARCHIVED"))).containsExactly("Blazer");
            assertThat(names(list("status=ALL"))).hasSize(4);
            mockMvc.perform(get("/api/v1/products?status=GONE").header("Authorization", owner.bearer()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        @Test
        void sorts() throws Exception {
            assertThat(names(list("sort=name,asc"))).containsExactly("Black Pants", "Blue Shirt", "Red Shirt");
            assertThat(names(list("sort=name,desc"))).containsExactly("Red Shirt", "Blue Shirt", "Black Pants");
            assertThat(names(list("sort=createdAt,desc"))).containsExactly("Red Shirt", "Black Pants", "Blue Shirt");
            // Default: recently updated first.
            assertThat(names(list("")).getFirst()).isEqualTo("Red Shirt");
            mockMvc.perform(get("/api/v1/products?sort=price,asc").header("Authorization", owner.bearer()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        @Test
        void minPriceSortUsesActiveVariants() throws Exception {
            String expensive = api.createOk(owner.bearer(), """
                    {"name":"Gold Watch","variants":[{"sku":"GW-1","price":500}]}""");
            assertThat(names(list("sort=minPrice,asc")).getFirst()).isEqualTo("Gold Watch");
            assertThat(names(list("sort=minPrice,desc")).getLast()).isEqualTo("Gold Watch");
            assertThat(id(expensive)).isNotBlank();
        }

        @Test
        void pagination() throws Exception {
            String page = list("sort=name,asc&size=2&page=1");
            assertThat(names(page)).containsExactly("Red Shirt");
            assertThat(JsonPath.<Integer>read(page, "$.totalElements")).isEqualTo(3);
            assertThat(JsonPath.<Integer>read(page, "$.totalPages")).isEqualTo(2);
            assertThat(JsonPath.<Integer>read(page, "$.page")).isEqualTo(1);
            assertThat(JsonPath.<Integer>read(page, "$.size")).isEqualTo(2);
            mockMvc.perform(get("/api/v1/products?size=101").header("Authorization", owner.bearer()))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void summaryFields() throws Exception {
            String page = list("q=black");
            assertThat(JsonPath.<Integer>read(page, "$.content[0].variantCount")).isEqualTo(2);
            assertThat(JsonPath.<Integer>read(page, "$.content[0].priceRange.min")).isEqualTo(1000);
            assertThat(JsonPath.<Integer>read(page, "$.content[0].priceRange.max")).isEqualTo(2000);
            assertThat(JsonPath.<Integer>read(page, "$.content[0].totalAvailable")).isZero();
        }

        @Test
        void categoriesWithCounts() throws Exception {
            String body = mockMvc.perform(get("/api/v1/products/categories").header("Authorization", owner.bearer()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<String>>read(body, "$[*].name")).hasSize(3);
            assertThat(JsonPath.<List<Integer>>read(body, "$[?(@.name =~ /shirts/i)].productCount")).containsExactly(2);
        }
    }

    // ==== Lookup cache ==================================================================

    @Nested
    class LookupCache {

        @Test
        void filledOnFirstCallAndEvictedOnEdit() throws Exception {
            String created = api.createOk(owner.bearer(), "Tee", "TEE-S");
            String productId = id(created);
            assertThat(lookupNames()).containsExactly("Tee");

            // Change the name behind the service's back: the cache still answers with the old one.
            mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(new ObjectId(productId))),
                    new Update().set("name", "Changed in DB"), "products");
            assertThat(lookupNames()).containsExactly("Tee");

            // A write through the API evicts the shop's entry.
            putProduct(productId, "Tee v2", 0).andExpect(status().isOk());
            assertThat(lookupNames()).containsExactly("Tee v2");
        }

        @Test
        void archivedProductsAndInactiveVariantsAreExcluded() throws Exception {
            String a = api.createOk(owner.bearer(), "Archived", "AR-1");
            String b = api.createOk(owner.bearer(), "Partly", "PA-1", "PA-2");
            String c = api.createOk(owner.bearer(), "All inactive", "AI-1");
            patchStatus(id(a), "ARCHIVED", 0).andExpect(status().isOk());
            patchVariant(id(b), variantId(b, 1), "{\"active\":false,\"version\":0}").andExpect(status().isOk());
            patchVariant(id(c), variantId(c, 0), "{\"active\":false,\"version\":0}").andExpect(status().isOk());

            String body = lookup();
            assertThat(JsonPath.<List<String>>read(body, "$[*].name")).containsExactly("Partly");
            assertThat(JsonPath.<List<String>>read(body, "$[0].variants[*].sku")).containsExactly("PA-1");
            assertThat(body).doesNotContain("costPrice").doesNotContain("stock");
        }

        private List<String> lookupNames() throws Exception {
            return JsonPath.read(lookup(), "$[*].name");
        }

        private String lookup() throws Exception {
            return mockMvc.perform(get("/api/v1/products/lookup").header("Authorization", owner.bearer()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        }
    }

    // ==== Images ========================================================================

    @Nested
    class Images {

        private String productId;

        @BeforeEach
        void product() throws Exception {
            productId = id(api.createOk(owner.bearer(), "Photo Tee", "PT-1"));
        }

        @Test
        void uploadStoresImageAndLeavesNoCleanupTask() throws Exception {
            String body = api.uploadOk(owner.bearer(), productId);

            assertThat(JsonPath.<String>read(body, "$.images[0].url")).startsWith("https://images.test/sellerdesk/" + owner.shopId());
            assertThat(body).doesNotContain("publicId");
            assertThat(imageStorage.uploads()).hasSize(1).allMatch(p -> p.startsWith("sellerdesk/" + owner.shopId() + "/products/"));
            assertThat(mongoTemplate.count(new Query(), ImageCleanupTask.class)).isZero();
        }

        @Test
        void over5MbIs413() throws Exception {
            api.upload(owner.bearer(), productId, TestImages.jpegOfSize(6 * 1024 * 1024), "image/jpeg")
                    .andExpect(status().is(413))
                    .andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"));
            assertThat(imageStorage.uploads()).isEmpty();
        }

        @Test
        void spoofedTypeIs400() throws Exception {
            api.upload(owner.bearer(), productId, TestImages.exe(), "image/png")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("IMAGE_TYPE_NOT_ALLOWED"));
            api.upload(owner.bearer(), productId, new byte[0], "image/png")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("IMAGE_TYPE_NOT_ALLOWED"));
            assertThat(imageStorage.uploads()).isEmpty();
        }

        @Test
        void ninthImageIs409() throws Exception {
            for (int i = 0; i < 8; i++) {
                api.uploadOk(owner.bearer(), productId);
            }
            api.upload(owner.bearer(), productId, TestImages.jpeg(), "image/jpeg")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IMAGE_LIMIT_REACHED"));
            assertThat(imageStorage.uploads()).hasSize(8);
        }

        @Test
        void parallelUploadsNeverExceed8() throws Exception {
            for (int i = 0; i < 6; i++) {
                api.uploadOk(owner.bearer(), productId);
            }
            List<Integer> statuses = runInParallel(6, i -> () -> api.upload(owner.bearer(), productId,
                    TestImages.png(), "image/png").andReturn().getResponse().getStatus());

            Document stored = mongoTemplate.findById(new ObjectId(productId), Document.class, "products");
            assertThat(stored.getList("images", Document.class)).hasSize(8);
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(2);
            assertThat(statuses).filteredOn(s -> s == 409).hasSize(4);
            // Uploaded files that lost the race were deleted right away.
            List<String> saved = stored.getList("images", Document.class).stream().map(d -> d.getString("publicId")).toList();
            List<String> lost = imageStorage.uploads().stream().filter(p -> !saved.contains(p)).toList();
            assertThat(imageStorage.deletes()).containsExactlyInAnyOrderElementsOf(lost);
        }

        @Test
        void storageFailureIs502AndNothingIsSaved() throws Exception {
            imageStorage.failUploads(true);
            api.upload(owner.bearer(), productId, TestImages.png(), "image/png")
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("IMAGE_UPLOAD_FAILED"));
            assertThat(JsonPath.<List<?>>read(api.fetch(owner.bearer(), productId), "$.images")).isEmpty();
            assertThat(mongoTemplate.count(new Query(), ImageCleanupTask.class)).isZero();
        }

        @Test
        void removeImageDeletesTheFile() throws Exception {
            String body = api.uploadOk(owner.bearer(), productId);
            String publicId = imageStorage.uploads().getFirst();

            mockMvc.perform(delete("/api/v1/products/{id}/images/{imageId}", productId, imageId(body, 0))
                            .header("Authorization", owner.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.images.length()").value(0));

            await(() -> imageStorage.deletes().contains(publicId));
            await(() -> mongoTemplate.count(new Query(), ImageCleanupTask.class) == 0);
        }

        @Test
        void reorderMovesCover() throws Exception {
            api.uploadOk(owner.bearer(), productId);
            String two = api.uploadOk(owner.bearer(), productId);
            String first = imageId(two, 0);
            String second = imageId(two, 1);

            reorder(productId, List.of(second, first), version(two))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.images[0].id").value(second));
            reorder(productId, List.of(second), version(two) + 1)
                    .andExpect(status().isBadRequest());
        }

        @Test
        void deletingProductDeletesItsFiles() throws Exception {
            api.uploadOk(owner.bearer(), productId);
            api.uploadOk(owner.bearer(), productId);

            mockMvc.perform(delete("/api/v1/products/{id}", productId).header("Authorization", owner.bearer()))
                    .andExpect(status().isNoContent());

            await(() -> imageStorage.deletes().containsAll(imageStorage.uploads()));
        }
    }

    // ==== Cleanup job ===================================================================

    @Nested
    class CleanupJob {

        @Test
        void deletesDueTasksAndRetriesFailures() {
            ImageCleanupTask due = task("due-file", Instant.now().minusSeconds(60));
            ImageCleanupTask later = task("later-file", Instant.now().plus(Duration.ofHours(1)));
            ImageCleanupTask failing = task("failing-file", Instant.now().minusSeconds(60));
            imageStorage.failDeleteOf("failing-file");

            int deleted = cleanupTasks.processDueTasks();

            assertThat(deleted).isEqualTo(1);
            assertThat(imageStorage.deletes()).containsExactly("due-file");
            assertThat(mongoTemplate.findById(due.getId(), ImageCleanupTask.class)).isNull();
            assertThat(mongoTemplate.findById(later.getId(), ImageCleanupTask.class)).isNotNull();
            ImageCleanupTask retried = mongoTemplate.findById(failing.getId(), ImageCleanupTask.class);
            assertThat(retried.getAttempts()).isEqualTo(1);
            assertThat(retried.getNotBefore()).isAfter(Instant.now());
        }

        private ImageCleanupTask task(String publicId, Instant notBefore) {
            ImageCleanupTask t = new ImageCleanupTask();
            t.setShopId(owner.shopId());
            t.setPublicId(publicId);
            t.setReason(ImageCleanupReason.ORPHAN_UPLOAD);
            t.setNotBefore(notBefore);
            t.setCreatedAt(Instant.now());
            return mongoTemplate.insert(t);
        }
    }

    // ==== API docs ======================================================================

    @Test
    void swaggerDocumentsProductEndpointsAndTheFileUpload() throws Exception {
        String docs = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<Object>read(docs, "$.paths['/api/v1/products']")).isNotNull();
        assertThat(JsonPath.<Object>read(docs,
                "$.paths['/api/v1/products/{id}/images'].post.requestBody.content['multipart/form-data']")).isNotNull();
        assertThat(JsonPath.<String>read(docs,
                "$.paths['/api/v1/products/{id}/images'].post.requestBody.content['multipart/form-data'].schema.properties.file.format"))
                .isEqualTo("binary");
    }

    // ---- helpers ----------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions putProduct(String productId, String name, long version)
            throws Exception {
        return send(put("/api/v1/products/{id}", productId),
                "{\"name\":\"%s\",\"version\":%d}".formatted(name, version));
    }

    private org.springframework.test.web.servlet.ResultActions patchStatus(String productId, String status, long version)
            throws Exception {
        return send(patch("/api/v1/products/{id}/status", productId),
                "{\"status\":\"%s\",\"version\":%d}".formatted(status, version));
    }

    private org.springframework.test.web.servlet.ResultActions addVariant(String productId, String sku, String size,
                                                                       long version) throws Exception {
        return send(post("/api/v1/products/{id}/variants", productId),
                "{\"sku\":\"%s\",\"attributes\":{\"Size\":\"%s\"},\"price\":1500,\"version\":%d}"
                        .formatted(sku, size, version));
    }

    private org.springframework.test.web.servlet.ResultActions patchVariant(String productId, String variantId, String json)
            throws Exception {
        return send(patch("/api/v1/products/{id}/variants/{variantId}", productId, variantId), json);
    }

    private org.springframework.test.web.servlet.ResultActions reorder(String productId, List<String> ids, long version)
            throws Exception {
        String array = ids.stream().map(i -> "\"" + i + "\"").reduce((a, b) -> a + "," + b).orElse("");
        return send(put("/api/v1/products/{id}/images/order", productId),
                "{\"imageIds\":[%s],\"version\":%d}".formatted(array, version));
    }

    private org.springframework.test.web.servlet.ResultActions send(MockHttpServletRequestBuilder request, String json)
            throws Exception {
        return mockMvc.perform(request.header("Authorization", owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String list(String query) throws Exception {
        return mockMvc.perform(get("/api/v1/products?" + query).header("Authorization", owner.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static List<String> names(String page) {
        return JsonPath.read(page, "$.content[*].name");
    }

    private static long versionOf(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return ProductTestApi.version(result.getResponse().getContentAsString());
    }

    /** Simulates Inventory: writes stock straight into the first variant, as $inc would. */
    private void setStock(String productId, int onHand, int reserved) {
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(new ObjectId(productId))),
                new Update().set("variants.0.stock", new Document("onHand", onHand).append("reserved", reserved)
                        .append("available", onHand - reserved)), "products");
    }

    private Document stockOf(String productId, int variantIndex) {
        Document stored = mongoTemplate.findById(new ObjectId(productId), Document.class, "products");
        return stored.getList("variants", Document.class).get(variantIndex).get("stock", Document.class);
    }

    interface IndexedTask {
        Callable<Integer> forIndex(int i);
    }

    /** Starts all calls at the same moment and returns their HTTP statuses. */
    private static List<Integer> runInParallel(int count, IndexedTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Callable<Integer> call = task.forIndex(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Waits up to 10 s for something done on a background (@Async) thread. */
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Condition not met within 10 s");
    }
}

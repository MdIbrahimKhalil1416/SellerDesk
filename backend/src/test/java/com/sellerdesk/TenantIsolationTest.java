package com.sellerdesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sellerdesk.common.config.AppProperties;
import com.sellerdesk.common.security.JwtService;
import com.sellerdesk.common.security.Role;
import com.sellerdesk.common.security.TenantContext;
import com.sellerdesk.product.ProductTestApi;
import com.sellerdesk.testsupport.MongoIntegrationTestBase;
import com.sellerdesk.testsupport.TestImages;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Proves that one shop can never see or change another shop's data (docs/SECURITY.md §4–5).
 *
 * <p>Every test starts with two real, verified shops, A and B, created through the real
 * register → email code → verify flow with unique emails. The database is wiped before each test
 * by {@link MongoIntegrationTestBase}.
 *
 * <h2>Adding a new tenant-owned module (staff, product, inventory, customer, order)</h2>
 * Every new module <b>must</b> add a {@code @Nested} class at the bottom of this file, following
 * the template in {@link CrossTenantById}:
 * <ol>
 *   <li>Create a record in shop B through the API with {@code shopB.bearer()}.</li>
 *   <li>For <b>every</b> endpoint with an {@code {id}}: call it as A with B's id via
 *       {@link #assertHiddenFromOtherShop}: expect {@code 404} (never 403: ids must not be probeable),
 *       and B's record must be unchanged.</li>
 *   <li>For every list endpoint: A's list contains none of B's records.</li>
 *   <li>For every create endpoint: send {@code "shopId": "<B>"} in the body as A; the record must
 *       be saved with A's shopId.</li>
 *   <li>Every referenced id (customerId, variantId...) from shop B must give {@code 404}.</li>
 * </ol>
 */
class TenantIsolationTest extends MongoIntegrationTestBase {

    @Autowired
    private AppProperties appProperties;

    private TestOwner shopA;
    private TestOwner shopB;

    @BeforeEach
    void createTwoShops() throws Exception {
        shopA = createVerifiedOwner("tenant-a");
        shopB = createVerifiedOwner("tenant-b");
        assertThat(shopA.shopId()).isNotEqualTo(shopB.shopId());
    }

    /** No request may leave its shopId behind on the (reused) thread. */
    @AfterEach
    void tenantContextIsEmptyAfterTheTest() {
        assertThat(TenantContext.getShopId()).isNull();
    }

    // ==== 1. Each owner sees only their own shop =====================================

    @Nested
    class OwnShopOnly {

        @Test
        void ownerAGetsShopAFromShopEndpoint() throws Exception {
            mockMvc.perform(get("/api/v1/shop").header("Authorization", shopA.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(shopA.shopId()))
                    .andExpect(jsonPath("$.name").value(shopA.shopName()));
        }

        @Test
        void ownerAGetsShopAFromMe() throws Exception {
            mockMvc.perform(get("/api/v1/auth/me").header("Authorization", shopA.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.id").value(shopA.userId()))
                    .andExpect(jsonPath("$.user.email").value(shopA.email()))
                    .andExpect(jsonPath("$.shop.id").value(shopA.shopId()));
        }

        @Test
        void ownerBGetsShopB() throws Exception {
            mockMvc.perform(get("/api/v1/shop").header("Authorization", shopB.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(shopB.shopId()));
            mockMvc.perform(get("/api/v1/auth/me").header("Authorization", shopB.bearer()))
                    .andExpect(jsonPath("$.shop.id").value(shopB.shopId()));
        }

        @Test
        void responsesNeverContainTheOtherShop() throws Exception {
            String shopBody = body(get("/api/v1/shop").header("Authorization", shopA.bearer()));
            String meBody = body(get("/api/v1/auth/me").header("Authorization", shopA.bearer()));

            assertThat(shopBody).doesNotContain(shopB.shopId()).doesNotContain(shopB.shopName());
            assertThat(meBody).doesNotContain(shopB.shopId()).doesNotContain(shopB.email());
        }
    }

    // ==== 2. Forged and expired tokens =================================================

    @Nested
    class ForgedTokens {

        @Test
        void shopIdClaimChangedToShopBWithOriginalSignatureIs401() throws Exception {
            String forged = replaceInPayload(shopA.accessToken(), shopA.shopId(), shopB.shopId());

            mockMvc.perform(get("/api/v1/shop").header("Authorization", "Bearer " + forged))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        }

        @Test
        void tokenSignedWithAnotherSecretIs401() throws Exception {
            JwtService attacker = new JwtService(
                    TestFixtures.properties("attacker-secret-attacker-secret-0123456789"), Clock.systemUTC());
            String forged = attacker.generateAccessToken(shopA.userId(), shopB.shopId(), Role.SHOP_OWNER,
                    Role.SHOP_OWNER.defaultPermissions(), 0);

            mockMvc.perform(get("/api/v1/shop").header("Authorization", "Bearer " + forged))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        }

        /**
         * Even a token signed with the real key is refused when its shopId differs from the user's
         * shop in the database (second line of defense if the key ever leaked or a bug issued it).
         */
        @Test
        void correctlySignedTokenWithAnotherUsersShopIdIs401() throws Exception {
            String wrongShop = jwtService.generateAccessToken(shopA.userId(), shopB.shopId(), Role.SHOP_OWNER,
                    Role.SHOP_OWNER.defaultPermissions(), 0);

            mockMvc.perform(get("/api/v1/shop").header("Authorization", "Bearer " + wrongShop))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        }

        @Test
        void expiredAccessTokenIs401TokenExpired() throws Exception {
            JwtService anHourAgo = new JwtService(appProperties,
                    Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC));
            String expired = anHourAgo.generateAccessToken(shopA.userId(), shopA.shopId(), Role.SHOP_OWNER,
                    Role.SHOP_OWNER.defaultPermissions(), 0);

            mockMvc.perform(get("/api/v1/shop").header("Authorization", "Bearer " + expired))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
        }

        @Test
        void refreshTokenUsedAsAccessTokenIs401() throws Exception {
            mockMvc.perform(get("/api/v1/shop").header("Authorization", "Bearer " + shopA.refreshCookie().getValue()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        }
    }

    // ==== 3. Revocation: tokenVersion is checked for access tokens on every request ===

    @Nested
    class Revocation {

        @Test
        void accessTokenIssuedBeforeLogoutIs401() throws Exception {
            mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", shopA.bearer()))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/v1/shop").header("Authorization", shopA.bearer()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        }

        @Test
        void accessTokenIssuedBeforePasswordResetIs401() throws Exception {
            clearInvocations(mailSender); // so the next code read is the reset code, not the verification one
            mockMvc.perform(post("/api/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + shopA.email() + "\"}"))
                    .andExpect(status().isAccepted());
            String code = awaitEmailedCode(shopA.email());
            mockMvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"%s\",\"code\":\"%s\",\"newPassword\":\"changed2026x\"}"
                                    .formatted(shopA.email(), code)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/v1/shop").header("Authorization", shopA.bearer()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        }

        @Test
        void logoutOfShopADoesNotAffectShopB() throws Exception {
            mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", shopA.bearer()))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/v1/shop").header("Authorization", shopB.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(shopB.shopId()));
        }
    }

    // ==== 4. shopId from the request is ignored: it always comes from the token ========

    @Nested
    class ShopIdFromRequestIsIgnored {

        @Test
        void shopIdQueryParameterIsIgnored() throws Exception {
            mockMvc.perform(get("/api/v1/shop").param("shopId", shopB.shopId()).header("Authorization", shopA.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(shopA.shopId()));
            mockMvc.perform(get("/api/v1/auth/me").param("shopId", shopB.shopId()).header("Authorization", shopA.bearer()))
                    .andExpect(jsonPath("$.shop.id").value(shopA.shopId()));
        }

        @Test
        void shopIdHeaderIsIgnored() throws Exception {
            mockMvc.perform(get("/api/v1/shop").header("X-Shop-Id", shopB.shopId()).header("Authorization", shopA.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(shopA.shopId()));
        }

        @Test
        void shopIdInLogoutBodyDoesNotLogOutShopB() throws Exception {
            mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", shopA.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"shopId\":\"%s\",\"userId\":\"%s\"}".formatted(shopB.shopId(), shopB.userId())))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/v1/shop").header("Authorization", shopB.bearer())).andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/shop").header("Authorization", shopA.bearer())).andExpect(status().isUnauthorized());
        }

        /** A new registration cannot attach itself to shop B or pick its own role/status. */
        @Test
        void shopIdRoleAndStatusInRegistrationBodyAreIgnored() throws Exception {
            String email = uniqueEmail("intruder");
            mockMvc.perform(post("/api/v1/auth/register-shop").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"ownerName":"Intruder","email":"%s","password":"intrude2026x",
                                     "shopName":"%s","shopPhone":"01812345678",
                                     "shopId":"%s","role":"PLATFORM_ADMIN","status":"ACTIVE",
                                     "permissions":["STAFF_MANAGE"]}
                                    """.formatted(email, uniqueName("Intruder Shop"), shopB.shopId())))
                    .andExpect(status().isCreated());

            Query intruder = new Query(Criteria.where("email").is(email));
            org.bson.Document user = mongoTemplate.findOne(intruder, org.bson.Document.class, "users");
            assertThat(user).isNotNull();
            assertThat(user.get("shopId").toString()).isNotEqualTo(shopB.shopId());
            assertThat(user.getString("role")).isEqualTo("SHOP_OWNER");
            assertThat(user.getString("status")).isEqualTo("PENDING_VERIFICATION");

            // Shop B still has exactly one user: its real owner.
            long usersOfB = mongoTemplate.count(new Query(Criteria.where("shopId")
                    .is(new org.bson.types.ObjectId(shopB.shopId()))), "users");
            assertThat(usersOfB).isOne();
        }
    }

    // ==== 5. No TenantContext leak between requests ===================================

    @Nested
    class NoContextLeak {

        @Test
        void tenantContextIsEmptyAfterEveryKindOfRequest() throws Exception {
            mockMvc.perform(get("/api/v1/shop").header("Authorization", shopA.bearer()));
            assertThat(TenantContext.getShopId()).isNull();

            mockMvc.perform(get("/api/v1/shop").header("Authorization", "Bearer not.a.token"));
            assertThat(TenantContext.getShopId()).isNull();

            mockMvc.perform(get("/api/v1/shop"));
            assertThat(TenantContext.getShopId()).isNull();
        }

        @Test
        void alternatingShopsAlwaysGetTheirOwnShop() throws Exception {
            // MockMvc runs every request on this same thread: exactly the "reused thread" case.
            for (int i = 0; i < 3; i++) {
                for (TestOwner owner : new TestOwner[] {shopA, shopB}) {
                    mockMvc.perform(get("/api/v1/shop").header("Authorization", owner.bearer()))
                            .andExpect(jsonPath("$.id").value(owner.shopId()));
                }
            }
        }

        @Test
        void anonymousRequestRightAfterShopAGetsNothingOfShopA() throws Exception {
            mockMvc.perform(get("/api/v1/shop").header("Authorization", shopA.bearer())).andExpect(status().isOk());

            mockMvc.perform(get("/api/v1/shop"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.id").doesNotExist());
        }
    }

    // ==== 6. Cross-tenant access by id (one @Nested class per module) ==================

    /**
     * Template for tenant-owned modules. No module has {@code {id}} endpoints yet; each one adds
     * its own class here when it is built, for example:
     *
     * <pre>{@code
     * @Nested
     * class Products {
     *     @Test
     *     void otherShopsProductIsNotFound() throws Exception {
     *         String productOfB = createProductAs(shopB);                 // via POST /api/v1/products
     *         assertHiddenFromOtherShop("/api/v1/products/" + productOfB);  // GET as A → 404, as B → 200
     *         // also PUT / PATCH / DELETE / action endpoints as A → 404, then B's product unchanged
     *     }
     * }
     * }</pre>
     *
     * Modules still to add: Staff, Inventory, Customers, Orders. Done: {@link Products}.
     */
    @Nested
    class CrossTenantById {
    }

    // ==== 7. Products ==================================================================

    @Nested
    class Products {

        private ProductTestApi api;
        private String productOfB;
        private String variantOfB;
        private String imageOfB;

        @BeforeEach
        void shopBHasAProductWithAnImage() throws Exception {
            api = new ProductTestApi(mockMvc);
            String created = api.createOk(shopB.bearer(), ProductTestApi.productJson("Beta Tee", "B Category", "B-SKU-1"));
            productOfB = ProductTestApi.id(created);
            variantOfB = ProductTestApi.variantId(created, 0);
            imageOfB = ProductTestApi.imageId(api.uploadOk(shopB.bearer(), productOfB), 0);
        }

        @Test
        void otherShopsProductIsNotFoundOnEveryIdEndpoint() throws Exception {
            org.bson.Document before = productDocument(productOfB);
            assertHiddenFromOtherShop("/api/v1/products/" + productOfB);

            String json = MediaType.APPLICATION_JSON_VALUE;
            assertNotFoundForA(put("/api/v1/products/{id}", productOfB).contentType(json)
                    .content("{\"name\":\"Hacked\",\"version\":1}"));
            assertNotFoundForA(patch("/api/v1/products/{id}/status", productOfB).contentType(json)
                    .content("{\"status\":\"ARCHIVED\",\"version\":1}"));
            assertNotFoundForA(post("/api/v1/products/{id}/variants", productOfB).contentType(json)
                    .content("{\"sku\":\"HACK-1\",\"price\":1,\"version\":1}"));
            assertNotFoundForA(patch("/api/v1/products/{id}/variants/{v}", productOfB, variantOfB).contentType(json)
                    .content("{\"price\":1,\"version\":1}"));
            assertNotFoundForA(multipart("/api/v1/products/{id}/images", productOfB)
                    .file(new MockMultipartFile("file", "a.png", "image/png", TestImages.png())));
            assertNotFoundForA(delete("/api/v1/products/{id}/images/{imageId}", productOfB, imageOfB));
            assertNotFoundForA(put("/api/v1/products/{id}/images/order", productOfB).contentType(json)
                    .content("{\"imageIds\":[\"" + imageOfB + "\"],\"version\":1}"));
            assertNotFoundForA(delete("/api/v1/products/{id}", productOfB));

            assertThat(productDocument(productOfB)).isEqualTo(before);
            assertThat(imageStorage.uploads()).hasSize(1); // only B's own upload
            assertThat(imageStorage.deletes()).isEmpty();
        }

        @Test
        void variantAndImageIdsOfShopBAreNotFoundOnShopAsProduct() throws Exception {
            String productOfA = ProductTestApi.id(api.createOk(shopA.bearer(), "Alpha Tee", "A-SKU-1"));
            String json = MediaType.APPLICATION_JSON_VALUE;

            assertNotFoundForA(patch("/api/v1/products/{id}/variants/{v}", productOfA, variantOfB).contentType(json)
                    .content("{\"price\":1,\"version\":0}"));
            assertNotFoundForA(delete("/api/v1/products/{id}/images/{imageId}", productOfA, imageOfB));
            assertThat(imageStorage.deletes()).isEmpty();
        }

        @Test
        void listsLookupAndCategoriesShowOnlyOwnProducts() throws Exception {
            api.createOk(shopA.bearer(), ProductTestApi.productJson("Alpha Tee", "A Category", "A-SKU-1"));
            // B also has a product whose name and SKU would match A's searches.
            api.createOk(shopB.bearer(), ProductTestApi.productJson("Alpha Bravo", "A Category", "SHARED-Q"));

            String all = body(get("/api/v1/products?status=ALL").header("Authorization", shopA.bearer()));
            assertThat(all).contains("Alpha Tee").doesNotContain("Beta Tee").doesNotContain("Alpha Bravo");
            String byName = body(get("/api/v1/products?q=alpha").header("Authorization", shopA.bearer()));
            assertThat(byName).contains("Alpha Tee").doesNotContain("Alpha Bravo");
            String bySku = body(get("/api/v1/products?q=SHARED-Q").header("Authorization", shopA.bearer()));
            assertThat(bySku).contains("\"totalElements\":0");
            String bySkuOfB = body(get("/api/v1/products?q=B-SKU-1").header("Authorization", shopA.bearer()));
            assertThat(bySkuOfB).contains("\"totalElements\":0");

            String lookup = body(get("/api/v1/products/lookup").header("Authorization", shopA.bearer()));
            assertThat(lookup).contains("Alpha Tee").doesNotContain("Beta Tee").doesNotContain("Alpha Bravo");
            String categories = body(get("/api/v1/products/categories").header("Authorization", shopA.bearer()));
            assertThat(categories).contains("A Category").doesNotContain("B Category").contains("\"productCount\":1");
        }

        @Test
        void shopIdInCreateAndUpdateBodyIsIgnored() throws Exception {
            String created = api.createOk(shopA.bearer(), """
                    {"name":"Sneaky","shopId":"%s","variants":[{"sku":"SNEAK-1","price":100,"shopId":"%s"}]}"""
                    .formatted(shopB.shopId(), shopB.shopId()));
            String productId = ProductTestApi.id(created);
            assertThat(productDocument(productId).get("shopId")).isEqualTo(new org.bson.types.ObjectId(shopA.shopId()));

            mockMvc.perform(put("/api/v1/products/{id}", productId).header("Authorization", shopA.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Still A\",\"shopId\":\"%s\",\"version\":0}".formatted(shopB.shopId())))
                    .andExpect(status().isOk());
            assertThat(productDocument(productId).get("shopId")).isEqualTo(new org.bson.types.ObjectId(shopA.shopId()));
            mockMvc.perform(get("/api/v1/products/{id}", productId).header("Authorization", shopB.bearer()))
                    .andExpect(status().isNotFound());
        }

        @Test
        void skuOwnedByShopBDoesNotBlockShopA() throws Exception {
            api.createOk(shopA.bearer(), "Same SKU in A", "B-SKU-1");
        }

        private void assertNotFoundForA(org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
            String body = mockMvc.perform(request.header("Authorization", shopA.bearer()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(shopB.shopId()).doesNotContain("Beta Tee");
        }

        private org.bson.Document productDocument(String productId) {
            return mongoTemplate.findById(new org.bson.types.ObjectId(productId), org.bson.Document.class, "products");
        }
    }

    // ---- helpers ----------------------------------------------------------------------

    /**
     * GET {@code path} (a record of shop B) as owner A → {@code 404} with no B data in the body;
     * as owner B → {@code 200}. 404, not 403, so ids of other shops cannot be probed.
     */
    protected void assertHiddenFromOtherShop(String path) throws Exception {
        String body = mockMvc.perform(get(path).header("Authorization", shopA.bearer()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(shopB.shopId());

        mockMvc.perform(get(path).header("Authorization", shopB.bearer()))
                .andExpect(status().isOk());
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        ResultActions result = mockMvc.perform(request).andExpect(status().isOk());
        return result.andReturn().getResponse().getContentAsString();
    }

    /** Changes text inside a JWT's payload but keeps the original signature. */
    private static String replaceInPayload(String jwt, String from, String to) {
        String[] parts = jwt.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(payload).contains(from);
        String changed = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.replace(from, to).getBytes(StandardCharsets.UTF_8));
        return parts[0] + "." + changed + "." + parts[2];
    }
}

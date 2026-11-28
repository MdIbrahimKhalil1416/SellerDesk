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
import com.sellerdesk.shop.ShopTestApi;
import com.sellerdesk.staff.StaffTestApi;
import com.sellerdesk.testsupport.MongoIntegrationTestBase;
import com.sellerdesk.testsupport.TestImages;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
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
 * The shop itself has no {@code {id}} endpoints ({@link ShopSettings}): there the shop always
 * comes from the token.
 */
class TenantIsolationTest extends MongoIntegrationTestBase {

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

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
     * Done: {@link Products}, {@link Staff}, {@link Inventory}, {@link Customers}, {@link Orders}.
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

    // ==== 8. Staff =====================================================================

    @Nested
    class Staff {

        private StaffTestApi api;
        private StaffTestApi.Member memberOfB;
        private String invitationOfB;
        private String invitedEmailOfB;

        @BeforeEach
        void shopBHasAMemberAndAPendingInvitation() throws Exception {
            api = new StaffTestApi(mockMvc, mailSender);
            memberOfB = api.inviteAndAccept(shopB.bearer(), uniqueEmail("beta-member"), "PACKER");
            invitedEmailOfB = uniqueEmail("beta-invited");
            invitationOfB = api.invite(shopB.bearer(), invitedEmailOfB, "MODERATOR");
        }

        @Test
        void otherShopsMemberIsNotFoundOnEveryIdEndpoint() throws Exception {
            org.bson.Document memberBefore = document("users", memberOfB.userId());
            org.bson.Document ownerBBefore = document("users", shopB.userId());
            assertHiddenFromOtherShop("/api/v1/staff/" + memberOfB.userId());
            assertHiddenFromOtherShop("/api/v1/staff/" + shopB.userId());

            String json = MediaType.APPLICATION_JSON_VALUE;
            for (String userId : new String[] {memberOfB.userId(), shopB.userId()}) {
                assertNotFoundForA(patch("/api/v1/staff/{id}", userId).contentType(json).content("{\"role\":\"MODERATOR\"}"));
                assertNotFoundForA(patch("/api/v1/staff/{id}/status", userId).contentType(json)
                        .content("{\"status\":\"DISABLED\"}"));
            }

            assertThat(document("users", memberOfB.userId())).isEqualTo(memberBefore);
            assertThat(document("users", shopB.userId())).isEqualTo(ownerBBefore);
            mockMvc.perform(get("/api/v1/auth/me").header("Authorization", memberOfB.bearer())).andExpect(status().isOk());
        }

        @Test
        void otherShopsInvitationIsNotFoundForResendAndCancel() throws Exception {
            org.bson.Document before = document("staff_invitations", invitationOfB);

            assertNotFoundForA(post("/api/v1/staff/invitations/{id}/resend", invitationOfB));
            assertNotFoundForA(delete("/api/v1/staff/invitations/{id}", invitationOfB));

            // Same token hash, send count and status: nothing was resent or cancelled.
            assertThat(document("staff_invitations", invitationOfB)).isEqualTo(before);
        }

        @Test
        void listShowsOnlyOwnMembersAndInvitations() throws Exception {
            String invitationOfA = api.invite(shopA.bearer(), uniqueEmail("alpha-invited"), "PACKER");

            String all = body(get("/api/v1/staff").header("Authorization", shopA.bearer()));
            assertThat(all).contains(shopA.userId()).contains(invitationOfA)
                    .doesNotContain(shopB.userId()).doesNotContain(memberOfB.userId()).doesNotContain(invitationOfB)
                    .doesNotContain(shopB.email()).contains("\"totalElements\":2");

            // Searches that match only B's records find nothing for A.
            for (String q : new String[] {"beta", memberOfB.email(), invitedEmailOfB, "tenant-b"}) {
                String found = body(get("/api/v1/staff").param("q", q).header("Authorization", shopA.bearer()));
                assertThat(found).as(q).contains("\"totalElements\":0");
            }
            for (String status : new String[] {"ACTIVE", "INVITED", "DISABLED", "EXPIRED"}) {
                String found = body(get("/api/v1/staff").param("status", status).header("Authorization", shopA.bearer()));
                assertThat(found).doesNotContain(memberOfB.userId()).doesNotContain(invitationOfB);
            }
        }

        @Test
        void sameEmailInvitedByBothShopsIsSeparateAndFirstAcceptWins() throws Exception {
            String email = uniqueEmail("both");
            api.invite(shopB.bearer(), email, "PACKER");
            String tokenFromB = api.awaitInviteToken(email);
            api.invite(shopA.bearer(), email, "ACCOUNTANT");
            String tokenFromA = api.awaitInviteToken(email, tokenFromB);

            api.accept(tokenFromA, "Both");
            mockMvc.perform(post("/api/v1/auth/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"%s\",\"name\":\"Both\",\"password\":\"both2026pass\"}".formatted(tokenFromB)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
        }

        @Test
        void shopIdInInviteAndPatchBodyIsIgnored() throws Exception {
            String email = uniqueEmail("sneaky");
            String created = bodyWithStatus(post("/api/v1/staff/invitations").header("Authorization", shopA.bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"%s\",\"role\":\"PACKER\",\"shopId\":\"%s\"}".formatted(email, shopB.shopId())),
                    201);
            String invitationId = com.jayway.jsonpath.JsonPath.read(created, "$.id");
            assertThat(document("staff_invitations", invitationId).get("shopId"))
                    .isEqualTo(new org.bson.types.ObjectId(shopA.shopId()));

            StaffTestApi.Member memberOfA = api.inviteAndAccept(shopA.bearer(), uniqueEmail("alpha-member"), "PACKER");
            mockMvc.perform(patch("/api/v1/staff/{id}", memberOfA.userId()).header("Authorization", shopA.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"MODERATOR\",\"shopId\":\"%s\"}".formatted(shopB.shopId())))
                    .andExpect(status().isOk());
            assertThat(document("users", memberOfA.userId()).get("shopId"))
                    .isEqualTo(new org.bson.types.ObjectId(shopA.shopId()));
            mockMvc.perform(get("/api/v1/staff/{id}", memberOfA.userId()).header("Authorization", shopB.bearer()))
                    .andExpect(status().isNotFound());
        }

        @Test
        void acceptingShopAsInvitationCreatesUserInShopAWhateverTheBodySays() throws Exception {
            String email = uniqueEmail("joiner");
            api.invite(shopA.bearer(), email, "PACKER");
            String token = api.awaitInviteToken(email);

            String body = bodyWithStatus(post("/api/v1/auth/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"token":"%s","name":"Joiner","password":"join2026pass",
                             "shopId":"%s","role":"SHOP_OWNER","email":"evil@example.com","permissions":["STAFF_MANAGE"]}
                            """.formatted(token, shopB.shopId())), 200);
            String userId = com.jayway.jsonpath.JsonPath.read(body, "$.user.id");
            org.bson.Document user = document("users", userId);
            assertThat(user.get("shopId")).isEqualTo(new org.bson.types.ObjectId(shopA.shopId()));
            assertThat(user.getString("role")).isEqualTo("PACKER");
            assertThat(user.getString("email")).isEqualTo(email);
            assertThat(user.getList("permissions", String.class)).doesNotContain("STAFF_MANAGE");
        }

        private void assertNotFoundForA(org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
            String body = mockMvc.perform(request.header("Authorization", shopA.bearer()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(shopB.shopId()).doesNotContain(memberOfB.email());
        }

        private org.bson.Document document(String collection, String id) {
            return mongoTemplate.findById(new org.bson.types.ObjectId(id), org.bson.Document.class, collection);
        }

        private String bodyWithStatus(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
            return mockMvc.perform(request).andExpect(status().is(expectedStatus))
                    .andReturn().getResponse().getContentAsString();
        }
    }

    /**
     * Shop settings ({@code PUT /shop}, {@code PUT/DELETE /shop/logo}). There is no
     * {@code /shop/{id}}: the shop is always the one in the token, so the attacks left are a
     * {@code shopId} smuggled into the body, query or header, and a cache shared across shops.
     */
    @Nested
    class ShopSettings {

        private org.bson.Document storedShop(TestOwner owner) {
            return mongoTemplate.findById(new org.bson.types.ObjectId(owner.shopId()), org.bson.Document.class, "shops");
        }

        @Test
        void shopIdOfBInBodyQueryAndHeaderChangesOnlyShopA() throws Exception {
            org.bson.Document shopBBefore = storedShop(shopB);
            String json = ShopTestApi.updateJson("Changed By A", "01812345678", "AAA")
                    .replaceFirst("\\{", "{\"shopId\":\"" + shopB.shopId() + "\",\"id\":\"" + shopB.shopId() + "\",");

            mockMvc.perform(ShopTestApi.update(json).param("shopId", shopB.shopId()).header("X-Shop-Id", shopB.shopId())
                            .header("Authorization", shopA.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(shopA.shopId()))
                    .andExpect(jsonPath("$.name").value("Changed By A"));

            assertThat(storedShop(shopA).getString("name")).isEqualTo("Changed By A");
            assertThat(storedShop(shopB)).isEqualTo(shopBBefore);
            mockMvc.perform(get("/api/v1/shop").header("Authorization", shopB.bearer()))
                    .andExpect(jsonPath("$.name").value(shopB.shopName()))
                    .andExpect(jsonPath("$.settings.orderNumberPrefix").value("SD"));
        }

        @Test
        void logoOfAIsStoredForAAndNeverTouchesB() throws Exception {
            mockMvc.perform(ShopTestApi.uploadLogo(TestImages.png()).header("Authorization", shopB.bearer()))
                    .andExpect(status().isOk());
            org.bson.Document shopBBefore = storedShop(shopB);

            mockMvc.perform(ShopTestApi.uploadLogo(TestImages.png()).param("shopId", shopB.shopId())
                            .header("Authorization", shopA.bearer()))
                    .andExpect(status().isOk());
            assertThat(storedShop(shopA).get("logo", org.bson.Document.class).getString("publicId"))
                    .startsWith("sellerdesk/" + shopA.shopId() + "/logo/");

            // A removes "its" logo: B's logo and file stay.
            mockMvc.perform(delete("/api/v1/shop/logo").param("shopId", shopB.shopId())
                            .header("Authorization", shopA.bearer()))
                    .andExpect(status().isOk());
            assertThat(storedShop(shopB)).isEqualTo(shopBBefore);
            String logoOfB = shopBBefore.get("logo", org.bson.Document.class).getString("publicId");
            Thread.sleep(200); // give the async cleanup listener a chance to (wrongly) run
            assertThat(imageStorage.deletes()).doesNotContain(logoOfB);
        }

        @Test
        void cachedSettingsArePerShop() throws Exception {
            com.sellerdesk.shop.service.ShopSettingsLookup lookup =
                    applicationContext.getBean(com.sellerdesk.shop.service.ShopSettingsLookup.class);
            assertThat(lookup.forShop(shopA.shopId()).orderNumberPrefix()).isEqualTo("SD");

            mockMvc.perform(ShopTestApi.update(ShopTestApi.updateJson("Shop B", "01812345678", "BBB"))
                            .header("Authorization", shopB.bearer()))
                    .andExpect(status().isOk());

            assertThat(lookup.forShop(shopB.shopId()).orderNumberPrefix()).isEqualTo("BBB");
            assertThat(lookup.forShop(shopA.shopId()).orderNumberPrefix()).isEqualTo("SD");
        }
    }

    // ==== 9. Inventory ==================================================================

    /**
     * Stock and movements (docs/modules/INVENTORY.md §8). The variant id in the path is the only
     * id the inventory endpoints take; B's id must look exactly like an unknown one to A.
     */
    @Nested
    class Inventory {

        private ProductTestApi products;
        private com.sellerdesk.inventory.InventoryTestApi inventory;
        private String productOfB;
        private String variantOfB;

        @BeforeEach
        void shopBHasStock() throws Exception {
            products = new ProductTestApi(mockMvc);
            inventory = new com.sellerdesk.inventory.InventoryTestApi(mockMvc);
            String created = products.createOk(shopB.bearer(), ProductTestApi.productJson("Beta Socks", null, "B-SOCK-1"));
            productOfB = ProductTestApi.id(created);
            variantOfB = ProductTestApi.variantId(created, 0);
            inventory.restockOk(shopB.bearer(), variantOfB, 3); // 3 available: also "low" for B
        }

        @Test
        void otherShopsVariantIsNotFoundOnEveryIdEndpointAndNothingIsWritten() throws Exception {
            org.bson.Document before = document("products", productOfB);
            long movementsBefore = mongoTemplate.count(new Query(), "stock_movements");

            assertHiddenFromOtherShop("/api/v1/inventory/variants/" + variantOfB);
            assertNotFoundForA(com.sellerdesk.inventory.InventoryTestApi.restockRequest(variantOfB,
                    com.sellerdesk.inventory.InventoryTestApi.restockJson(5, java.util.UUID.randomUUID().toString())));
            assertNotFoundForA(com.sellerdesk.inventory.InventoryTestApi.adjustRequest(variantOfB,
                    com.sellerdesk.inventory.InventoryTestApi.adjustJson(-1, "LOST", null, java.util.UUID.randomUUID().toString())));

            assertThat(document("products", productOfB)).isEqualTo(before);
            assertThat(mongoTemplate.count(new Query(), "stock_movements")).isEqualTo(movementsBefore);
        }

        @Test
        void listsLowStockAndHistoryShowOnlyOwnData() throws Exception {
            String created = products.createOk(shopA.bearer(), ProductTestApi.productJson("Alpha Socks", null, "A-SOCK-1"));
            String variantOfA = ProductTestApi.variantId(created, 0);
            inventory.restockOk(shopA.bearer(), variantOfA, 2);

            String list = body(get("/api/v1/inventory?includeInactive=true").header("Authorization", shopA.bearer()));
            assertThat(list).contains("A-SOCK-1").doesNotContain("B-SOCK-1").doesNotContain(variantOfB)
                    .contains("\"totalElements\":1");
            String low = body(get("/api/v1/inventory/low-stock").header("Authorization", shopA.bearer()));
            assertThat(low).contains("A-SOCK-1").doesNotContain("B-SOCK-1");
            String history = body(get("/api/v1/inventory/movements").header("Authorization", shopA.bearer()));
            assertThat(history).contains("A-SOCK-1").doesNotContain("B-SOCK-1").contains("\"totalElements\":1");

            // Search and filters with B's SKU / ids: empty, never B's rows.
            for (String url : new String[] {
                    "/api/v1/inventory?q=B-SOCK-1", "/api/v1/inventory?q=beta",
                    "/api/v1/inventory/movements?variantId=" + variantOfB,
                    "/api/v1/inventory/movements?productId=" + productOfB}) {
                assertThat(body(get(url).header("Authorization", shopA.bearer()))).as(url)
                        .contains("\"totalElements\":0").doesNotContain("B-SOCK-1");
            }
        }

        @Test
        void shopIdAndProductIdInTheBodyAreIgnored() throws Exception {
            String created = products.createOk(shopA.bearer(), ProductTestApi.productJson("Alpha Cap", null, "A-CAP-1"));
            String productOfA = ProductTestApi.id(created);
            String variantOfA = ProductTestApi.variantId(created, 0);
            org.bson.Document bBefore = document("products", productOfB);

            String json = ("{\"quantity\":4,\"shopId\":\"%s\",\"productId\":\"%s\",\"variantId\":\"%s\","
                    + "\"requestId\":\"%s\"}").formatted(shopB.shopId(), productOfB, variantOfB, java.util.UUID.randomUUID());
            mockMvc.perform(com.sellerdesk.inventory.InventoryTestApi.restockRequest(variantOfA, json)
                            .header("Authorization", shopA.bearer()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.movement.productId").value(productOfA))
                    .andExpect(jsonPath("$.movement.variantId").value(variantOfA));

            org.bson.Document movement = mongoTemplate.findOne(new Query(Criteria.where("variantId")
                    .is(new org.bson.types.ObjectId(variantOfA))), org.bson.Document.class, "stock_movements");
            assertThat(movement.get("shopId")).isEqualTo(new org.bson.types.ObjectId(shopA.shopId()));
            assertThat(document("products", productOfB)).isEqualTo(bBefore);
        }

        @Test
        void aRequestIdUsedByShopBDoesNotBlockShopA() throws Exception {
            String requestId = java.util.UUID.randomUUID().toString();
            mockMvc.perform(com.sellerdesk.inventory.InventoryTestApi.restockRequest(variantOfB,
                            com.sellerdesk.inventory.InventoryTestApi.restockJson(1, requestId))
                            .header("Authorization", shopB.bearer()))
                    .andExpect(status().isCreated());

            String variantOfA = ProductTestApi.variantId(
                    products.createOk(shopA.bearer(), ProductTestApi.productJson("Alpha Hat", null, "A-HAT-1")), 0);
            mockMvc.perform(com.sellerdesk.inventory.InventoryTestApi.restockRequest(variantOfA,
                            com.sellerdesk.inventory.InventoryTestApi.restockJson(1, requestId))
                            .header("Authorization", shopA.bearer()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.movement.variantId").value(variantOfA))
                    .andExpect(jsonPath("$.movement.type").value("INITIAL"));
        }

        private void assertNotFoundForA(MockHttpServletRequestBuilder request) throws Exception {
            String responseBody = mockMvc.perform(request.header("Authorization", shopA.bearer()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(responseBody).doesNotContain(shopB.shopId()).doesNotContain("B-SOCK-1");
        }

        private org.bson.Document document(String collection, String id) {
            return mongoTemplate.findById(new org.bson.types.ObjectId(id), org.bson.Document.class, collection);
        }
    }

    // ==== 10. Customers =================================================================

    /**
     * Customers (docs/modules/CUSTOMER.md §7). Ids of another shop must look like unknown ids,
     * the same phone may exist in both shops, and phone lookup / search never cross shops.
     */
    @Nested
    class Customers {

        private com.sellerdesk.customer.CustomerTestApi api;
        private String customerOfB;
        private String addressOfB;

        @BeforeEach
        void shopBHasACustomer() throws Exception {
            api = new com.sellerdesk.customer.CustomerTestApi(mockMvc);
            String created = api.createOk(shopB.bearer(), "Beta Buyer", "01712345678");
            customerOfB = com.sellerdesk.customer.CustomerTestApi.id(created);
            addressOfB = com.sellerdesk.customer.CustomerTestApi.addressId(created, 0);
        }

        @Test
        void otherShopsCustomerIsNotFoundOnEveryIdEndpoint() throws Exception {
            org.bson.Document before = customerDocument(customerOfB);
            assertHiddenFromOtherShop("/api/v1/customers/" + customerOfB);

            String json = MediaType.APPLICATION_JSON_VALUE;
            assertNotFoundForA(put("/api/v1/customers/{id}", customerOfB).contentType(json)
                    .content("{\"name\":\"Hacked\",\"phone\":\"01712345678\",\"version\":0}"));
            assertNotFoundForA(patch("/api/v1/customers/{id}/status", customerOfB).contentType(json)
                    .content("{\"status\":\"ARCHIVED\",\"version\":0}"));
            assertNotFoundForA(post("/api/v1/customers/{id}/addresses", customerOfB).contentType(json)
                    .content(com.sellerdesk.customer.CustomerTestApi.addressJson("Hacked", true)));
            assertNotFoundForA(put("/api/v1/customers/{id}/addresses/{a}", customerOfB, addressOfB).contentType(json)
                    .content(com.sellerdesk.customer.CustomerTestApi.addressJson("Hacked", true)));
            assertNotFoundForA(delete("/api/v1/customers/{id}/addresses/{a}", customerOfB, addressOfB));

            assertThat(customerDocument(customerOfB)).isEqualTo(before);
        }

        @Test
        void addressIdOfShopBIsNotFoundOnShopAsCustomer() throws Exception {
            String customerOfA = com.sellerdesk.customer.CustomerTestApi.id(api.createOk(shopA.bearer(), "Alpha Buyer", "01812345678"));
            String json = MediaType.APPLICATION_JSON_VALUE;

            assertNotFoundForA(put("/api/v1/customers/{id}/addresses/{a}", customerOfA, addressOfB).contentType(json)
                    .content(com.sellerdesk.customer.CustomerTestApi.addressJson("X", false)));
            assertNotFoundForA(delete("/api/v1/customers/{id}/addresses/{a}", customerOfA, addressOfB));
        }

        @Test
        void listSearchLookupAndTagsShowOnlyOwnCustomers() throws Exception {
            mockMvc.perform(put("/api/v1/customers/{id}", customerOfB).header("Authorization", shopB.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Beta Buyer\",\"phone\":\"01712345678\",\"tags\":[\"B-ONLY\"],\"version\":0}"))
                    .andExpect(status().isOk());
            api.createOk(shopA.bearer(), "Alpha Buyer", "01812345678");

            String all = body(get("/api/v1/customers?status=ALL").header("Authorization", shopA.bearer()));
            assertThat(all).contains("Alpha Buyer").doesNotContain("Beta Buyer").doesNotContain(customerOfB)
                    .contains("\"totalElements\":1");
            for (String q : new String[] {"Beta", "0171", "5678", "01712345678"}) {
                String found = body(get("/api/v1/customers").param("q", q).header("Authorization", shopA.bearer()));
                assertThat(found).as(q).doesNotContain("Beta Buyer");
            }
            String tags = body(get("/api/v1/customers/tags").header("Authorization", shopA.bearer()));
            assertThat(tags).doesNotContain("B-ONLY");

            String lookup = mockMvc.perform(get("/api/v1/customers/lookup").param("phone", "01712345678")
                            .header("Authorization", shopA.bearer()))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            assertThat(lookup).doesNotContain("Beta Buyer").doesNotContain(customerOfB);
        }

        @Test
        void shopIdInBodyIsIgnoredAndBsPhoneIsFreeForA() throws Exception {
            String created = api.createOk(shopA.bearer(), "Alpha Same Phone", "01712345678");
            String customerOfA = com.sellerdesk.customer.CustomerTestApi.id(created);
            assertThat(customerDocument(customerOfA).get("shopId")).isEqualTo(new org.bson.types.ObjectId(shopA.shopId()));

            String sneaky = api.create(shopA.bearer(), """
                            {"name":"Sneaky","phone":"01912345678","shopId":"%s"}""".formatted(shopB.shopId()))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            String sneakyId = com.sellerdesk.customer.CustomerTestApi.id(sneaky);
            assertThat(customerDocument(sneakyId).get("shopId")).isEqualTo(new org.bson.types.ObjectId(shopA.shopId()));
            mockMvc.perform(get("/api/v1/customers/{id}", sneakyId).header("Authorization", shopB.bearer()))
                    .andExpect(status().isNotFound());
        }

        private void assertNotFoundForA(MockHttpServletRequestBuilder request) throws Exception {
            String responseBody = mockMvc.perform(request.header("Authorization", shopA.bearer()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(responseBody).doesNotContain(shopB.shopId()).doesNotContain("Beta Buyer");
        }

        private org.bson.Document customerDocument(String id) {
            return mongoTemplate.findById(new org.bson.types.ObjectId(id), org.bson.Document.class, "customers");
        }
    }

    // ==== 11. Orders ====================================================================

    /**
     * Orders (docs/modules/ORDER.md §11). Every order endpoint takes an order id; a status change
     * on another shop's order would also move that shop's stock and customer counters, so those
     * are checked unchanged too. Referenced ids (customer, variant) of another shop are 404.
     */
    @Nested
    class Orders {

        private com.sellerdesk.order.OrderTestApi api;
        private ProductTestApi products;
        private String variantOfB;
        private String orderOfB;
        private String customerOfB;

        @BeforeEach
        void shopBHasAnOrder() throws Exception {
            api = new com.sellerdesk.order.OrderTestApi(mockMvc);
            products = new ProductTestApi(mockMvc);
            variantOfB = ProductTestApi.variantId(products.createOk(shopB.bearer(), "Beta Kurta", "B-KURTA-1"), 0);
            new com.sellerdesk.inventory.InventoryTestApi(mockMvc).restockOk(shopB.bearer(), variantOfB, 5);
            orderOfB = api.createOrder(shopB.bearer(), variantOfB, 1);
            customerOfB = document("orders", orderOfB).get("customerId").toString();
        }

        @Test
        void otherShopsOrderIsNotFoundOnEveryIdEndpointAndNothingMoves() throws Exception {
            // B's order is CONFIRMED so that every action would have a side effect if it got through.
            api.actionOk(shopB.bearer(), orderOfB, "confirm");
            org.bson.Document orderBefore = document("orders", orderOfB);
            org.bson.Document productBefore = productOf(variantOfB);
            org.bson.Document customerBefore = document("customers", customerOfB);
            long movementsBefore = mongoTemplate.count(new org.springframework.data.mongodb.core.query.Query(), "stock_movements");

            assertHiddenFromOtherShop("/api/v1/orders/" + orderOfB);
            assertNotFoundForA(com.sellerdesk.order.OrderTestApi.updateRequest(orderOfB, 1,
                    List.of(com.sellerdesk.order.OrderTestApi.line(variantOfB, 1)), ""));
            for (String action : List.of("confirm", "pack", "ship", "deliver", "return", "cancel")) {
                assertNotFoundForA(com.sellerdesk.order.OrderTestApi.actionRequest(orderOfB, action));
            }

            assertThat(document("orders", orderOfB)).isEqualTo(orderBefore);
            assertThat(productOf(variantOfB)).isEqualTo(productBefore);
            assertThat(document("customers", customerOfB)).isEqualTo(customerBefore);
            assertThat(mongoTemplate.count(new org.springframework.data.mongodb.core.query.Query(), "stock_movements"))
                    .isEqualTo(movementsBefore);
        }

        @Test
        void customerAndVariantIdsOfShopBAreNotFoundAndTakeNoOrderNumber() throws Exception {
            String variantOfA = ProductTestApi.variantId(products.createOk(shopA.bearer(), "Alpha Kurta", "A-KURTA-1"), 0);

            assertNotFoundForA(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                    .content(com.sellerdesk.order.OrderTestApi.orderJson(com.sellerdesk.order.OrderTestApi.line(variantOfB, 1))));
            assertNotFoundForA(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("""
                    {"requestId":"%s","customerId":"%s","items":[%s],"source":"PHONE"}"""
                    .formatted(java.util.UUID.randomUUID(), customerOfB, com.sellerdesk.order.OrderTestApi.line(variantOfA, 1))));
            assertThat(document("shops", shopA.shopId()).get("orderSeq", Number.class).longValue()).isZero();
            assertThat(mongoTemplate.count(new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("shopId")
                            .is(new org.bson.types.ObjectId(shopA.shopId()))), "orders")).isZero();

            // A PENDING edit of A's own order may not add B's variant either.
            String orderOfA = api.createOrder(shopA.bearer(), variantOfA, 1);
            assertNotFoundForA(com.sellerdesk.order.OrderTestApi.updateRequest(orderOfA, 0, List.of(
                    com.sellerdesk.order.OrderTestApi.line(variantOfA, 1), com.sellerdesk.order.OrderTestApi.line(variantOfB, 1)), ""));
            assertThat(document("orders", orderOfA).getList("items", org.bson.Document.class)).hasSize(1);
        }

        @Test
        void shopIdInBodyIsIgnoredAndNumbersAndRequestIdsArePerShop() throws Exception {
            String variantOfA = ProductTestApi.variantId(products.createOk(shopA.bearer(), "Alpha Kurta", "A-KURTA-1"), 0);
            String requestIdOfB = document("orders", orderOfB).getString("requestId");

            // Same requestId as B's order: no clash, A gets its own order #1.
            String json = com.sellerdesk.order.OrderTestApi.orderJson(requestIdOfB, "01712345678", "INSIDE_DHAKA",
                    List.of(com.sellerdesk.order.OrderTestApi.line(variantOfA, 1)), ",\"shopId\":\"" + shopB.shopId() + "\"");
            String created = api.create(shopA.bearer(), json)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.orderNumber").value("SD-1"))
                    .andReturn().getResponse().getContentAsString();
            String orderOfA = com.sellerdesk.order.OrderTestApi.id(created);
            assertThat(orderOfA).isNotEqualTo(orderOfB);
            assertThat(document("orders", orderOfA).get("shopId")).isEqualTo(new org.bson.types.ObjectId(shopA.shopId()));
            // A's customer with the same phone is A's own record, not B's.
            assertThat(document("orders", orderOfA).get("customerId").toString()).isNotEqualTo(customerOfB);
            mockMvc.perform(get("/api/v1/orders/{id}", orderOfA).header("Authorization", shopB.bearer()))
                    .andExpect(status().isNotFound());

            mockMvc.perform(com.sellerdesk.order.OrderTestApi.updateRequest(orderOfA, 0,
                            List.of(com.sellerdesk.order.OrderTestApi.line(variantOfA, 2)), ",\"shopId\":\"" + shopB.shopId() + "\"")
                            .header("Authorization", shopA.bearer()))
                    .andExpect(status().isOk());
            assertThat(document("orders", orderOfA).get("shopId")).isEqualTo(new org.bson.types.ObjectId(shopA.shopId()));
        }

        private void assertNotFoundForA(MockHttpServletRequestBuilder request) throws Exception {
            String responseBody = mockMvc.perform(request.header("Authorization", shopA.bearer()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(responseBody).doesNotContain(shopB.shopId()).doesNotContain("Beta Kurta").doesNotContain("B-KURTA-1");
        }

        private org.bson.Document document(String collection, String id) {
            return mongoTemplate.findById(new org.bson.types.ObjectId(id), org.bson.Document.class, collection);
        }

        private org.bson.Document productOf(String variantId) {
            return mongoTemplate.findOne(new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("variants.variantId")
                            .is(new org.bson.types.ObjectId(variantId))), org.bson.Document.class, "products");
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

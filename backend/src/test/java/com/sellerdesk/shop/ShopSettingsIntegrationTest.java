package com.sellerdesk.shop;

import static com.sellerdesk.shop.ShopTestApi.update;
import static com.sellerdesk.shop.ShopTestApi.updateJson;
import static com.sellerdesk.shop.ShopTestApi.uploadLogo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sellerdesk.common.storage.ImageCleanupTask;
import com.sellerdesk.shop.model.ShopSettings;
import com.sellerdesk.shop.service.ShopSettingsLookup;
import com.sellerdesk.testsupport.MongoIntegrationTestBase;
import com.sellerdesk.testsupport.TestImages;
import java.time.Duration;
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

/** {@code PUT /shop}, the logo endpoints and the settings cache, against a real MongoDB. */
class ShopSettingsIntegrationTest extends MongoIntegrationTestBase {

    @Autowired
    private ShopSettingsLookup settingsLookup;

    private TestOwner owner;

    @BeforeEach
    void setUp() throws Exception {
        owner = createVerifiedOwner("settings");
    }

    @Nested
    class ProfileAndSettings {

        @Test
        void ownerUpdatesEverythingAndGetShopReturnsTheNormalizedValues() throws Exception {
            mockMvc.perform(update(updateJson("New Name", "+880 1812-345678", "rf24")).header("Authorization", owner.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("New Name"))
                    .andExpect(jsonPath("$.phone").value("01812345678"))
                    .andExpect(jsonPath("$.email").value("hello@shop.com"))
                    .andExpect(jsonPath("$.settings.orderNumberPrefix").value("RF24"));

            mockMvc.perform(get("/api/v1/shop").header("Authorization", owner.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("New Name"))
                    .andExpect(jsonPath("$.phone").value("01812345678"))
                    .andExpect(jsonPath("$.address.district").value("Dhaka"))
                    .andExpect(jsonPath("$.facebookPageUrl").value("https://facebook.com/shop"))
                    .andExpect(jsonPath("$.logoUrl").doesNotExist())
                    .andExpect(jsonPath("$.settings.deliveryChargeInsideDhaka").value(7000))
                    .andExpect(jsonPath("$.settings.deliveryChargeOutsideDhaka").value(15000))
                    .andExpect(jsonPath("$.settings.defaultLowStockThreshold").value(3))
                    .andExpect(jsonPath("$.settings.orderNumberPrefix").value("RF24"));

            Document stored = storedShop();
            assertThat(stored.getString("phone")).isEqualTo("01812345678");
            Document settings = stored.get("settings", Document.class);
            assertThat(settings.getLong("deliveryChargeInsideDhaka")).isEqualTo(7000L);
            assertThat(settings.getString("orderNumberPrefix")).isEqualTo("RF24");
            assertThat(settings).doesNotContainKey("_class"); // a plain embedded document
        }

        @Test
        void updateNeverResetsTheOrderCounterOrOtherServerFields() throws Exception {
            // As if 41 orders had been created after the owner opened the settings page.
            mongoTemplate.updateFirst(byShopId(), new Update().set("orderSeq", 41L), "shops");
            Document before = storedShop();

            mockMvc.perform(update(updateJson("New Name", "01712345678", "SD")).header("Authorization", owner.bearer()))
                    .andExpect(status().isOk());

            Document after = storedShop();
            assertThat(after.getLong("orderSeq")).isEqualTo(41L);
            assertThat(after.get("ownerUserId")).isEqualTo(before.get("ownerUserId"));
            assertThat(after.getString("status")).isEqualTo("ACTIVE");
            assertThat(after.get("createdAt")).isEqualTo(before.get("createdAt"));
            assertThat(after.getLong("version")).isEqualTo(before.getLong("version") + 1);
        }

        @Test
        void invalidValuesGive400WithOneFieldErrorEach() throws Exception {
            String json = """
                    {"name":"X","phone":"01212345678",
                     "settings":{"deliveryChargeInsideDhaka":-1,"deliveryChargeOutsideDhaka":500001,
                                 "defaultLowStockThreshold":-5,"orderNumberPrefix":"TOOLONG"}}
                    """;
            mockMvc.perform(update(json).header("Authorization", owner.bearer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors.length()").value(6))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'name')]").exists())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'phone')].message")
                            .value("must be a Bangladeshi mobile number like 01712345678 (a +880 or 880 prefix is allowed)"))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'settings.deliveryChargeInsideDhaka')]").exists())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'settings.deliveryChargeOutsideDhaka')]").exists())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'settings.defaultLowStockThreshold')]").exists())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'settings.orderNumberPrefix')].message")
                            .value("must be 2 to 6 letters (A-Z) or digits, e.g. SD or RF24"));

            assertThat(storedShop().getString("name")).isNotEqualTo("X");
        }

        @Test
        void missingSettingsIs400() throws Exception {
            mockMvc.perform(update("{\"name\":\"Shop\",\"phone\":\"01712345678\"}").header("Authorization", owner.bearer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("settings"));
        }

        @Test
        void optionalFieldsLeftOutAreCleared() throws Exception {
            mockMvc.perform(update(updateJson("Shop", "01712345678", "SD")).header("Authorization", owner.bearer()))
                    .andExpect(status().isOk());
            mockMvc.perform(update("""
                            {"name":"Shop","phone":"01712345678","email":"",
                             "settings":{"deliveryChargeInsideDhaka":0,"deliveryChargeOutsideDhaka":0,
                                         "defaultLowStockThreshold":0,"orderNumberPrefix":"SD"}}
                            """).header("Authorization", owner.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").doesNotExist())
                    .andExpect(jsonPath("$.address").doesNotExist())
                    .andExpect(jsonPath("$.facebookPageUrl").doesNotExist());

            assertThat(storedShop()).doesNotContainKeys("email", "address", "facebookPageUrl");
        }

        @Test
        void shopCreatedBeforeThePrefixExistedGetsTheDefault() throws Exception {
            mongoTemplate.updateFirst(byShopId(), new Update().unset("settings.orderNumberPrefix"), "shops");

            mockMvc.perform(get("/api/v1/shop").header("Authorization", owner.bearer()))
                    .andExpect(jsonPath("$.settings.orderNumberPrefix").value("SD"))
                    .andExpect(jsonPath("$.settings.deliveryChargeInsideDhaka").value(6000));
            assertThat(settingsLookup.forShop(owner.shopId()).orderNumberPrefix()).isEqualTo("SD");
        }
    }

    @Nested
    class SettingsCache {

        @Test
        void settingsAreCachedAndEvictedByAnUpdate() throws Exception {
            settingsLookup.evict(owner.shopId()); // start clean: other tests share the cache
            assertThat(settingsLookup.forShop(owner.shopId())).isEqualTo(ShopSettings.defaults());

            // Changed behind the cache's back: the cached value is still served (proves caching).
            mongoTemplate.updateFirst(byShopId(), new Update().set("settings.defaultLowStockThreshold", 99), "shops");
            assertThat(settingsLookup.forShop(owner.shopId()).defaultLowStockThreshold()).isEqualTo(5);

            // A change through the API evicts: the next read sees the new values.
            mockMvc.perform(update(updateJson("Shop", "01712345678", "NEW")).header("Authorization", owner.bearer()))
                    .andExpect(status().isOk());
            assertThat(settingsLookup.forShop(owner.shopId()))
                    .isEqualTo(new ShopSettings(7000, 15000, 3, "NEW"));
        }
    }

    @Nested
    class Logo {

        @Test
        void uploadReplaceAndDelete() throws Exception {
            // Upload
            String first = mockMvc.perform(uploadLogo(TestImages.png()).header("Authorization", owner.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.logoUrl").exists())
                    .andExpect(jsonPath("$.publicId").doesNotExist())
                    .andReturn().getResponse().getContentAsString();
            String firstPublicId = storedLogo().getString("publicId");
            assertThat(firstPublicId).startsWith("sellerdesk/" + owner.shopId() + "/logo/");
            assertThat(imageStorage.uploads()).containsExactly(firstPublicId);
            mockMvc.perform(get("/api/v1/shop").header("Authorization", owner.bearer()))
                    .andExpect(jsonPath("$.logoUrl").value(JsonPath.<String>read(first, "$.logoUrl")));

            // Replace: new file stored, old file deleted, no cleanup task left behind
            mockMvc.perform(uploadLogo(TestImages.webp()).header("Authorization", owner.bearer()))
                    .andExpect(status().isOk());
            String secondPublicId = storedLogo().getString("publicId");
            assertThat(secondPublicId).isNotEqualTo(firstPublicId);
            await(() -> imageStorage.deletes().contains(firstPublicId));
            await(() -> mongoTemplate.count(new Query(), ImageCleanupTask.class) == 0);
            assertThat(imageStorage.deletes()).doesNotContain(secondPublicId);

            // Delete
            mockMvc.perform(delete("/api/v1/shop/logo").header("Authorization", owner.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.logoUrl").doesNotExist());
            assertThat(storedShop()).doesNotContainKey("logo");
            await(() -> imageStorage.deletes().contains(secondPublicId));
            await(() -> mongoTemplate.count(new Query(), ImageCleanupTask.class) == 0);

            // Delete again: no logo, no change, still 200
            mockMvc.perform(delete("/api/v1/shop/logo").header("Authorization", owner.bearer()))
                    .andExpect(status().isOk());
        }

        @Test
        void logoAndSettingsDoNotOverwriteEachOther() throws Exception {
            mockMvc.perform(uploadLogo(TestImages.png()).header("Authorization", owner.bearer())).andExpect(status().isOk());
            mockMvc.perform(update(updateJson("Shop", "01712345678", "SD")).header("Authorization", owner.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.logoUrl").exists());
            assertThat(storedShop()).containsKey("logo");
        }

        @Test
        void notAnImageIs400AndNothingIsUploaded() throws Exception {
            mockMvc.perform(uploadLogo(TestImages.exe()).header("Authorization", owner.bearer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("IMAGE_TYPE_NOT_ALLOWED"));
            assertThat(imageStorage.uploads()).isEmpty();
            assertThat(storedShop()).doesNotContainKey("logo");
        }

        @Test
        void overFiveMegabytesIs413() throws Exception {
            mockMvc.perform(uploadLogo(TestImages.jpegOfSize(6 * 1024 * 1024)).header("Authorization", owner.bearer()))
                    .andExpect(status().isContentTooLarge())
                    .andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"));
            assertThat(imageStorage.uploads()).isEmpty();
        }

        @Test
        void storageFailureIs502AndTheOldLogoStays() throws Exception {
            mockMvc.perform(uploadLogo(TestImages.png()).header("Authorization", owner.bearer())).andExpect(status().isOk());
            String kept = storedLogo().getString("publicId");
            imageStorage.failUploads(true);

            mockMvc.perform(uploadLogo(TestImages.png()).header("Authorization", owner.bearer()))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("IMAGE_UPLOAD_FAILED"));
            assertThat(storedLogo().getString("publicId")).isEqualTo(kept);
            assertThat(imageStorage.deletes()).isEmpty();
            assertThat(mongoTemplate.count(new Query(), ImageCleanupTask.class)).isZero();
        }
    }

    // ---- helpers ----------------------------------------------------------------------

    private Query byShopId() {
        return new Query(Criteria.where("_id").is(new ObjectId(owner.shopId())));
    }

    private Document storedShop() {
        return mongoTemplate.findOne(byShopId(), Document.class, "shops");
    }

    private Document storedLogo() {
        return storedShop().get("logo", Document.class);
    }

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

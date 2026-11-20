package com.sellerdesk.product;

import static com.sellerdesk.product.ProductTestApi.id;
import static com.sellerdesk.product.ProductTestApi.imageId;
import static com.sellerdesk.product.ProductTestApi.productJson;
import static com.sellerdesk.product.ProductTestApi.variantId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import com.sellerdesk.testsupport.MongoIntegrationTestBase;
import com.sellerdesk.testsupport.TestImages;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * {@code @PreAuthorize} on every product endpoint, with real users per role (inserted directly
 * until the staff module can invite them). Missing permission → {@code 403 FORBIDDEN}.
 */
class ProductPermissionTest extends MongoIntegrationTestBase {

    private ProductTestApi api;
    private TestOwner owner;
    private String productId;
    private String variantId;
    private String imageId;

    @BeforeEach
    void setUp() throws Exception {
        api = new ProductTestApi(mockMvc);
        owner = createVerifiedOwner("perm");
        String created = api.createOk(owner.bearer(), productJson("Tee", "Shirts", "TEE-1"));
        productId = id(created);
        variantId = variantId(created, 0);
        imageId = imageId(api.uploadOk(owner.bearer(), productId), 0);
    }

    /** Every write endpoint, with a valid body, so only the permission can fail. */
    private List<AbstractMockHttpServletRequestBuilder<?>> writeRequests() {
        String json = MediaType.APPLICATION_JSON_VALUE;
        return List.of(
                post("/api/v1/products").contentType(json).content(productJson("New", null, "NEW-1")),
                put("/api/v1/products/{id}", productId).contentType(json).content("{\"name\":\"X\",\"version\":1}"),
                patch("/api/v1/products/{id}/status", productId).contentType(json)
                        .content("{\"status\":\"ARCHIVED\",\"version\":1}"),
                post("/api/v1/products/{id}/variants", productId).contentType(json)
                        .content("{\"sku\":\"NEW-2\",\"price\":1,\"version\":1}"),
                patch("/api/v1/products/{id}/variants/{v}", productId, variantId).contentType(json)
                        .content("{\"price\":1,\"version\":1}"),
                multipart("/api/v1/products/{id}/images", productId)
                        .file(new MockMultipartFile("file", "a.png", "image/png", TestImages.png())),
                delete("/api/v1/products/{id}/images/{imageId}", productId, imageId),
                put("/api/v1/products/{id}/images/order", productId).contentType(json)
                        .content("{\"imageIds\":[\"" + imageId + "\"],\"version\":1}"),
                delete("/api/v1/products/{id}", productId));
    }

    @Test
    void packerGets403OnEveryWriteAndNothingChanges() throws Exception {
        String packer = bearerForStaff(owner.shopId(), Role.PACKER);
        Document before = storedProduct();

        for (AbstractMockHttpServletRequestBuilder<?> request : writeRequests()) {
            mockMvc.perform(request.header("Authorization", packer))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        assertThat(storedProduct()).isEqualTo(before);
        assertThat(mongoTemplate.count(new org.springframework.data.mongodb.core.query.Query(), "products")).isOne();
        assertThat(imageStorage.uploads()).hasSize(1); // only the owner's upload from setUp
    }

    @Test
    void packerCanRead() throws Exception {
        String packer = bearerForStaff(owner.shopId(), Role.PACKER);
        mockMvc.perform(get("/api/v1/products").header("Authorization", packer)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/products/{id}", productId).header("Authorization", packer)).andExpect(status().isOk());
    }

    @Test
    void withoutProductViewListDetailAndCategoriesAre403ButLookupWorksWithOrderCreate() throws Exception {
        String orderTaker = bearerForStaff(owner.shopId(), Role.MODERATOR, EnumSet.of(Permission.ORDER_CREATE));

        mockMvc.perform(get("/api/v1/products").header("Authorization", orderTaker)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/products/{id}", productId).header("Authorization", orderTaker))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/products/categories").header("Authorization", orderTaker))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/products/lookup").header("Authorization", orderTaker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(productId));
    }

    @Test
    void noRelevantPermissionMeansLookupIs403() throws Exception {
        String none = bearerForStaff(owner.shopId(), Role.ACCOUNTANT, EnumSet.of(Permission.REPORT_VIEW));
        mockMvc.perform(get("/api/v1/products/lookup").header("Authorization", none)).andExpect(status().isForbidden());
    }

    @Test
    void costPriceHiddenForModeratorShownForAccountantAndOwner() throws Exception {
        String moderator = bearerForStaff(owner.shopId(), Role.MODERATOR);
        String accountant = bearerForStaff(owner.shopId(), Role.ACCOUNTANT);

        mockMvc.perform(get("/api/v1/products/{id}", productId).header("Authorization", moderator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[0].costPrice").doesNotExist())
                .andExpect(jsonPath("$.variants[0].stock").exists());
        mockMvc.perform(get("/api/v1/products/{id}", productId).header("Authorization", accountant))
                .andExpect(jsonPath("$.variants[0].costPrice").value(500));
        mockMvc.perform(get("/api/v1/products/{id}", productId).header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.variants[0].costPrice").value(500));
    }

    @Test
    void stockHiddenWithoutStockView() throws Exception {
        String viewer = bearerForStaff(owner.shopId(), Role.MODERATOR, EnumSet.of(Permission.PRODUCT_VIEW));

        mockMvc.perform(get("/api/v1/products/{id}", productId).header("Authorization", viewer))
                .andExpect(jsonPath("$.variants[0].stock").doesNotExist())
                .andExpect(jsonPath("$.variants[0].costPrice").doesNotExist());
        mockMvc.perform(get("/api/v1/products").header("Authorization", viewer))
                .andExpect(jsonPath("$.content[0].totalAvailable").doesNotExist());
    }

    @Test
    void platformAdminGets403Everywhere() throws Exception {
        String admin = bearerForStaff(null, Role.PLATFORM_ADMIN, Set.of());

        mockMvc.perform(get("/api/v1/products").header("Authorization", admin)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/products/lookup").header("Authorization", admin)).andExpect(status().isForbidden());
        for (AbstractMockHttpServletRequestBuilder<?> request : writeRequests()) {
            mockMvc.perform(request.header("Authorization", admin)).andExpect(status().isForbidden());
        }
    }

    @Test
    void noTokenIs401() throws Exception {
        mockMvc.perform(get("/api/v1/products")).andExpect(status().isUnauthorized());
    }

    private Document storedProduct() {
        return mongoTemplate.findById(new ObjectId(productId), Document.class, "products");
    }
}

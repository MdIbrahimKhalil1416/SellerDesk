package com.sellerdesk.product;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sellerdesk.testsupport.TestImages;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Small helpers to call the product API in integration tests. */
public final class ProductTestApi {

    private final MockMvc mockMvc;

    public ProductTestApi(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    /** JSON for a product whose variants are {@code {"Size": "S<i>"}} at {@code (i+1) * 1000} poisha. */
    public static String productJson(String name, String category, String... skus) {
        List<String> variants = new ArrayList<>();
        for (int i = 0; i < skus.length; i++) {
            variants.add("""
                    {"sku":"%s","attributes":{"Size":"S%d"},"price":%d,"costPrice":500}"""
                    .formatted(skus[i], i, (i + 1) * 1000));
        }
        String categoryJson = category == null ? "null" : "\"" + category + "\"";
        return """
                {"name":"%s","category":%s,"variants":[%s]}""".formatted(name, categoryJson, String.join(",", variants));
    }

    public ResultActions create(String bearer, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/products").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** Creates the product (expects 201) and returns the response body. */
    public String createOk(String bearer, String json) throws Exception {
        return create(bearer, json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    public String createOk(String bearer, String name, String... skus) throws Exception {
        return createOk(bearer, productJson(name, null, skus));
    }

    public String fetch(String bearer, String productId) throws Exception {
        return mockMvc.perform(get("/api/v1/products/{id}", productId).header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    public ResultActions upload(String bearer, String productId, byte[] bytes, String contentType) throws Exception {
        return mockMvc.perform(multipart("/api/v1/products/{id}/images", productId)
                .file(new MockMultipartFile("file", "photo.png", contentType, bytes))
                .header("Authorization", bearer));
    }

    public String uploadOk(String bearer, String productId) throws Exception {
        return upload(bearer, productId, TestImages.png(), "image/png")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    public static String id(String json) {
        return JsonPath.read(json, "$.id");
    }

    public static long version(String json) {
        return ((Number) JsonPath.read(json, "$.version")).longValue();
    }

    public static String variantId(String json, int index) {
        return JsonPath.read(json, "$.variants[" + index + "].variantId");
    }

    public static String imageId(String json, int index) {
        return JsonPath.read(json, "$.images[" + index + "].id");
    }
}

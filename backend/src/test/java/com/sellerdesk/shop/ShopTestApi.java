package com.sellerdesk.shop;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/** Request builders for the shop endpoints, shared by the shop, permission and isolation tests. */
public final class ShopTestApi {

    private ShopTestApi() {
    }

    /** A valid {@code PUT /shop} body. */
    public static String updateJson(String name, String phone, String prefix) {
        return """
                {"name":"%s","phone":"%s","email":"Hello@Shop.COM",
                 "address":{"line":"House 1, Road 2","district":"Dhaka","zone":"INSIDE_DHAKA"},
                 "facebookPageUrl":"https://facebook.com/shop",
                 "settings":{"deliveryChargeInsideDhaka":7000,"deliveryChargeOutsideDhaka":15000,
                             "defaultLowStockThreshold":3,"orderNumberPrefix":"%s"}}
                """.formatted(name, phone, prefix);
    }

    public static MockHttpServletRequestBuilder update(String json) {
        return put("/api/v1/shop").contentType(MediaType.APPLICATION_JSON).content(json);
    }

    public static MockMultipartHttpServletRequestBuilder uploadLogo(byte[] bytes) {
        return (MockMultipartHttpServletRequestBuilder) multipart(HttpMethod.PUT, "/api/v1/shop/logo")
                .file(new MockMultipartFile("file", "logo.png", "image/png", bytes));
    }
}

package com.sellerdesk.customer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Small helpers to call the customer API in integration tests. */
public final class CustomerTestApi {

    private final MockMvc mockMvc;

    public CustomerTestApi(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    public static String customerJson(String name, String phone) {
        return """
                {"name":"%s","phone":"%s","addresses":[
                  {"label":"Home","line":"House 5, Road 3","area":"Mirpur","district":"Dhaka","zone":"INSIDE_DHAKA"}]}"""
                .formatted(name, phone);
    }

    public static String addressJson(String line, boolean isDefault) {
        return """
                {"line":"%s","district":"Chattogram","zone":"OUTSIDE_DHAKA","isDefault":%s}""".formatted(line, isDefault);
    }

    public ResultActions create(String bearer, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/customers").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** Creates the customer (expects 201) and returns the response body. */
    public String createOk(String bearer, String name, String phone) throws Exception {
        return create(bearer, customerJson(name, phone)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    public static String id(String json) {
        return JsonPath.read(json, "$.id");
    }

    public static String addressId(String json, int index) {
        return JsonPath.read(json, "$.addresses[" + index + "].addressId");
    }
}

package com.sellerdesk.customer;

import static com.sellerdesk.customer.CustomerTestApi.addressJson;
import static com.sellerdesk.customer.CustomerTestApi.customerJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import com.sellerdesk.testsupport.MongoIntegrationTestBase;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The HTTP layer of {@code CustomerController}: {@code 403} per role, {@code 404}, {@code 409}
 * and validation {@code 400}s, with real users per role (docs/SECURITY.md §3: CUSTOMER_VIEW for
 * owner, moderator, accountant; CUSTOMER_EDIT for owner and moderator; PACKER has neither).
 */
class CustomerControllerTest extends MongoIntegrationTestBase {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private TestOwner owner;
    private String customerId;
    private String addressId;

    @BeforeEach
    void setUp() throws Exception {
        owner = createVerifiedOwner("cust-http");
        String created = new CustomerTestApi(mockMvc).createOk(owner.bearer(), "Rahima", "01712345678");
        customerId = CustomerTestApi.id(created);
        addressId = CustomerTestApi.addressId(created, 0);
    }

    private List<MockHttpServletRequestBuilder> readRequests() {
        return List.of(get("/api/v1/customers"), get("/api/v1/customers/tags"),
                get("/api/v1/customers/{id}", customerId), get("/api/v1/customers/lookup").param("phone", "01712345678"));
    }

    private List<MockHttpServletRequestBuilder> writeRequests() {
        return List.of(
                post("/api/v1/customers").contentType(JSON).content(customerJson("New", "01812345678")),
                put("/api/v1/customers/{id}", customerId).contentType(JSON).content("{\"name\":\"X\",\"phone\":\"01712345678\",\"version\":0}"),
                patch("/api/v1/customers/{id}/status", customerId).contentType(JSON).content("{\"status\":\"ARCHIVED\",\"version\":0}"),
                post("/api/v1/customers/{id}/addresses", customerId).contentType(JSON).content(addressJson("Shop 1", false)),
                put("/api/v1/customers/{id}/addresses/{a}", customerId, addressId).contentType(JSON).content(addressJson("Shop 2", false)),
                delete("/api/v1/customers/{id}/addresses/{a}", customerId, addressId));
    }

    @Nested
    class Permissions {

        @Test
        void packerHasNoCustomerAccessAtAll() throws Exception {
            String packer = bearerForStaff(owner.shopId(), Role.PACKER);
            Document before = stored();
            for (MockHttpServletRequestBuilder request : readRequests()) {
                mockMvc.perform(request.header("Authorization", packer))
                        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            }
            for (MockHttpServletRequestBuilder request : writeRequests()) {
                mockMvc.perform(request.header("Authorization", packer)).andExpect(status().isForbidden());
            }
            assertThat(stored()).isEqualTo(before);
            assertThat(mongoTemplate.count(new Query(), "customers")).isOne();
        }

        @Test
        void accountantReadsButCannotWrite() throws Exception {
            String accountant = bearerForStaff(owner.shopId(), Role.ACCOUNTANT);
            for (MockHttpServletRequestBuilder request : readRequests()) {
                mockMvc.perform(request.header("Authorization", accountant)).andExpect(status().isOk());
            }
            for (MockHttpServletRequestBuilder request : writeRequests()) {
                mockMvc.perform(request.header("Authorization", accountant)).andExpect(status().isForbidden());
            }
        }

        @Test
        void moderatorCanWrite() throws Exception {
            String moderator = bearerForStaff(owner.shopId(), Role.MODERATOR);
            mockMvc.perform(post("/api/v1/customers").header("Authorization", moderator).contentType(JSON)
                    .content(customerJson("By Moderator", "01812345678"))).andExpect(status().isCreated());
        }

        @Test
        void orderCreateAloneAllowsOnlyLookup() throws Exception {
            String orderTaker = bearerForStaff(owner.shopId(), Role.MODERATOR, EnumSet.of(Permission.ORDER_CREATE));
            mockMvc.perform(get("/api/v1/customers/lookup").param("phone", "+8801712345678").header("Authorization", orderTaker))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(customerId));
            mockMvc.perform(get("/api/v1/customers").header("Authorization", orderTaker)).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/customers/{id}", customerId).header("Authorization", orderTaker)).andExpect(status().isForbidden());
        }

        @Test
        void platformAdminIs403AndNoTokenIs401() throws Exception {
            String admin = bearerForStaff(null, Role.PLATFORM_ADMIN, Set.of());
            for (MockHttpServletRequestBuilder request : readRequests()) {
                mockMvc.perform(request.header("Authorization", admin)).andExpect(status().isForbidden());
            }
            mockMvc.perform(get("/api/v1/customers")).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    class Errors {

        @Test
        void unknownOrMalformedIdsAre404() throws Exception {
            for (String id : List.of(new ObjectId().toHexString(), "not-an-id")) {
                mockMvc.perform(get("/api/v1/customers/{id}", id).header("Authorization", owner.bearer()))
                        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
                mockMvc.perform(put("/api/v1/customers/{id}", id).header("Authorization", owner.bearer()).contentType(JSON)
                        .content("{\"name\":\"X\",\"phone\":\"01712345678\",\"version\":0}")).andExpect(status().isNotFound());
                mockMvc.perform(delete("/api/v1/customers/{id}/addresses/{a}", customerId, id).header("Authorization", owner.bearer()))
                        .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Address not found"));
            }
            mockMvc.perform(get("/api/v1/customers/lookup").param("phone", "01999999999").header("Authorization", owner.bearer()))
                    .andExpect(status().isNotFound());
        }

        @Test
        void conflictsAre409() throws Exception {
            mockMvc.perform(post("/api/v1/customers").header("Authorization", owner.bearer()).contentType(JSON)
                            .content(customerJson("Dup", "8801712345678")))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PHONE_ALREADY_EXISTS"));
            mockMvc.perform(patch("/api/v1/customers/{id}/status", customerId).header("Authorization", owner.bearer())
                            .contentType(JSON).content("{\"status\":\"ARCHIVED\",\"version\":7}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
        }

        @Test
        void validationErrorsNameTheField() throws Exception {
            expectFieldError(post("/api/v1/customers").contentType(JSON).content("{\"phone\":\"01812345678\"}"), "name");
            expectFieldError(post("/api/v1/customers").contentType(JSON).content(customerJson("X", "01212345678")), "phone");
            expectFieldError(post("/api/v1/customers").contentType(JSON).content(customerJson("X", "")), "phone");
            expectFieldError(post("/api/v1/customers").contentType(JSON)
                    .content("{\"name\":\"X\",\"phone\":\"01812345678\",\"altPhone\":\"12345\"}"), "altPhone");
            expectFieldError(post("/api/v1/customers").contentType(JSON)
                    .content("{\"name\":\"X\",\"phone\":\"01812345678\",\"altPhone\":\"+8801812345678\"}"), "altPhone");
            expectFieldError(post("/api/v1/customers").contentType(JSON)
                    .content("{\"name\":\"X\",\"phone\":\"01812345678\",\"facebookProfileUrl\":\"facebook.com/x\"}"), "facebookProfileUrl");
            expectFieldError(post("/api/v1/customers").contentType(JSON)
                    .content("{\"name\":\"X\",\"phone\":\"01812345678\",\"tags\":[\"%s\"]}".formatted("t".repeat(31))), "tags[0]");
            expectFieldError(post("/api/v1/customers").contentType(JSON)
                    .content("{\"name\":\"X\",\"phone\":\"01812345678\",\"addresses\":[{\"line\":\"\",\"district\":\"Dhaka\"}]}"),
                    "addresses[0].line");
            expectFieldError(post("/api/v1/customers").contentType(JSON)
                    .content("{\"name\":\"X\",\"phone\":\"01812345678\",\"addresses\":[{\"line\":\"A\",\"district\":\"Dhaka\"}]}"),
                    "addresses[0].zone");
            expectFieldError(put("/api/v1/customers/{id}", customerId).contentType(JSON)
                    .content("{\"name\":\"X\",\"phone\":\"01712345678\"}"), "version");
            expectFieldError(post("/api/v1/customers/{id}/addresses", customerId).contentType(JSON)
                    .content("{\"line\":\"A\",\"zone\":\"INSIDE_DHAKA\"}"), "district");
            for (String url : List.of("/api/v1/customers?status=DELETED", "/api/v1/customers?sort=phone,asc",
                    "/api/v1/customers?size=101")) {
                mockMvc.perform(get(url).header("Authorization", owner.bearer()))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            }
            assertThat(mongoTemplate.count(new Query(), "customers")).isOne();
        }

        private void expectFieldError(MockHttpServletRequestBuilder request, String field) throws Exception {
            mockMvc.perform(request.header("Authorization", owner.bearer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == '" + field + "')]").exists());
        }
    }

    private Document stored() {
        return mongoTemplate.findById(new ObjectId(customerId), Document.class, "customers");
    }
}

package com.sellerdesk.customer;

import static com.sellerdesk.customer.CustomerTestApi.addressId;
import static com.sellerdesk.customer.CustomerTestApi.addressJson;
import static com.sellerdesk.customer.CustomerTestApi.customerJson;
import static com.sellerdesk.customer.CustomerTestApi.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sellerdesk.common.dto.AddressDto;
import com.sellerdesk.common.model.DeliveryZone;
import com.sellerdesk.common.security.AppUserDetails;
import com.sellerdesk.common.security.Role;
import com.sellerdesk.common.security.TenantContext;
import com.sellerdesk.common.transaction.TransactionRunner;
import com.sellerdesk.customer.service.CustomerService;
import com.sellerdesk.customer.service.NewOrderCustomer;
import com.sellerdesk.customer.service.OrderCustomer;
import com.sellerdesk.testsupport.MongoIntegrationTestBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * Customers against a real MongoDB: unique phone per shop, search through the phone indexes,
 * addresses, versioning, and the Java API for Order (docs/modules/CUSTOMER.md §7).
 */
class CustomerIntegrationTest extends MongoIntegrationTestBase {

    @Autowired
    private CustomerService customerService;

    @Autowired
    private TransactionRunner transactionRunner;

    private CustomerTestApi api;
    private TestOwner owner;

    @BeforeEach
    void setUp() throws Exception {
        api = new CustomerTestApi(mockMvc);
        owner = createVerifiedOwner("customers");
    }

    // ==== Create, update, archive =========================================================

    @Nested
    class Lifecycle {

        @Test
        void createStoresNormalizedPhoneAndStartsWithZeroCounters() throws Exception {
            api.create(owner.bearer(), """
                            {"name":"  Rahima   Begum ","phone":"+880 1712-345678","altPhone":"০১৮১২৩৪৫৬৭৮",
                             "tags":["VIP"," vip "],"note":"Calls after 8pm","shopId":"%s","stats":{"totalOrders":99},
                             "addresses":[{"line":"House 5","district":"Dhaka","zone":"INSIDE_DHAKA"}]}"""
                            .formatted(new ObjectId().toHexString()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Rahima Begum"))
                    .andExpect(jsonPath("$.phone").value("01712345678"))
                    .andExpect(jsonPath("$.altPhone").value("01812345678"))
                    .andExpect(jsonPath("$.tags.length()").value(1))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.stats.totalOrders").value(0))
                    .andExpect(jsonPath("$.addresses[0].isDefault").value(true))
                    .andExpect(jsonPath("$.version").value(0));

            Document stored = mongoTemplate.findOne(new Query(), Document.class, "customers");
            assertThat(stored.get("shopId")).isEqualTo(new ObjectId(owner.shopId()));
            assertThat(stored.getList("phoneKeysReversed", String.class)).containsExactly("87654321710", "87654321810");
            assertThat(stored.getList("addresses", Document.class).get(0).get("addressId")).isInstanceOf(ObjectId.class);
        }

        @Test
        void samePhoneInAnySpellingOrArchivedIs409() throws Exception {
            String created = api.createOk(owner.bearer(), "First", "01712345678");
            for (String phone : List.of("01712345678", "+8801712345678", "8801712345678", "01712 345-678")) {
                api.create(owner.bearer(), customerJson("Second", phone))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("PHONE_ALREADY_EXISTS"))
                        .andExpect(jsonPath("$.fieldErrors[0].field").value("phone"));
            }
            mockMvc.perform(patch("/api/v1/customers/{id}/status", id(created)).header("Authorization", owner.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ARCHIVED\",\"version\":0}"))
                    .andExpect(status().isOk());
            api.create(owner.bearer(), customerJson("Again", "01712345678")).andExpect(status().isConflict());
        }

        @Test
        void parallelCreatesWithOnePhoneExactlyOneWins() throws Exception {
            List<Integer> statuses = runInParallel(5, i -> api.create(owner.bearer(), customerJson("Racer " + i, "01912345678"))
                    .andReturn().getResponse().getStatus());
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
            assertThat(statuses).filteredOn(s -> s == 409).hasSize(4);
            assertThat(mongoTemplate.count(new Query(), "customers")).isOne();
        }

        @Test
        void updateArchiveRestoreAndStaleVersion() throws Exception {
            String customerId = id(api.createOk(owner.bearer(), "Rahima", "01712345678"));
            String other = id(api.createOk(owner.bearer(), "Karim", "01812345678"));

            mockMvc.perform(put("/api/v1/customers/{id}", customerId).header("Authorization", owner.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Rahima B\",\"phone\":\"01812345678\",\"version\":0}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PHONE_ALREADY_EXISTS"));
            mockMvc.perform(put("/api/v1/customers/{id}", customerId).header("Authorization", owner.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Rahima B\",\"phone\":\"+8801712345678\",\"tags\":[\"Wholesale\"],\"version\":0}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Rahima B"))
                    .andExpect(jsonPath("$.version").value(1))
                    .andExpect(jsonPath("$.addresses.length()").value(1)); // addresses untouched
            mockMvc.perform(put("/api/v1/customers/{id}", customerId).header("Authorization", owner.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\",\"phone\":\"01712345678\",\"version\":0}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));

            mockMvc.perform(patch("/api/v1/customers/{id}/status", customerId).header("Authorization", owner.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ARCHIVED\",\"version\":1}"))
                    .andExpect(jsonPath("$.status").value("ARCHIVED"));
            expectNames("/api/v1/customers", "Karim");
            expectNames("/api/v1/customers?status=ARCHIVED", "Rahima B");
            mockMvc.perform(patch("/api/v1/customers/{id}/status", customerId).header("Authorization", owner.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\",\"version\":2}"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
            assertThat(other).isNotNull();
        }

        @Test
        void samePhoneInTwoShopsIsAllowed() throws Exception {
            TestOwner second = createVerifiedOwner("customers-2");
            api.createOk(owner.bearer(), "In A", "01712345678");
            api.createOk(second.bearer(), "In B", "01712345678");
        }
    }

    // ==== Search ===========================================================================

    @Nested
    class Search {

        @BeforeEach
        void customers() throws Exception {
            api.createOk(owner.bearer(), "Rahima Begum", "01712345678");
            api.create(owner.bearer(), """
                            {"name":"Karim Uddin","phone":"01812349999","altPhone":"01612345678","tags":["VIP"]}""")
                    .andExpect(status().isCreated());
            api.create(owner.bearer(), """
                            {"name":"rahat khan","phone":"01912340000","tags":["vip","Wholesale"]}""")
                    .andExpect(status().isCreated());
        }

        @Test
        void byNamePrefixPhonePrefixLastDigitsAltPhoneAndTag() throws Exception {
            expectNames("/api/v1/customers?q=Rah&sort=name,asc", "rahat khan", "Rahima Begum"); // case-insensitive sort
            expectNames("/api/v1/customers?q=Begum"); // prefix only, not "contains"
            expectNames("/api/v1/customers?q=0171", "Rahima Begum");
            expectNames("/api/v1/customers?q=8801912", "rahat khan"); // 880 prefix -> 0
            expectNames("/api/v1/customers?q=5678&sort=name,asc", "Karim Uddin", "Rahima Begum"); // ends with, alt phone too
            expectNames("/api/v1/customers?q=016123", "Karim Uddin"); // alt phone prefix
            expectNames("/api/v1/customers?tag=VIP&sort=name,asc", "Karim Uddin", "rahat khan");
            expectNames("/api/v1/customers?sort=name,asc&size=2&page=1", "Rahima Begum");
        }

        @Test
        void tagListCountsActiveCustomersIgnoringCase() throws Exception {
            mockMvc.perform(get("/api/v1/customers/tags").header("Authorization", owner.bearer()))
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].name").value("VIP"))
                    .andExpect(jsonPath("$[0].customerCount").value(2))
                    .andExpect(jsonPath("$[1].name").value("Wholesale"));
        }

        @Test
        void lookupIsExactOnTheMainPhoneOnly() throws Exception {
            mockMvc.perform(get("/api/v1/customers/lookup").param("phone", "+880 1812-349999").header("Authorization", owner.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Karim Uddin"));
            mockMvc.perform(get("/api/v1/customers/lookup").param("phone", "01612345678").header("Authorization", owner.bearer()))
                    .andExpect(status().isNotFound()); // alt phone is not looked up
            mockMvc.perform(get("/api/v1/customers/lookup").param("phone", "0181234").header("Authorization", owner.bearer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("phone"));
        }
    }

    // ==== Addresses =========================================================================

    @Nested
    class Addresses {

        @Test
        void addUpdateMoveDefaultRemoveAndLimit() throws Exception {
            String created = api.createOk(owner.bearer(), "Rahima", "01712345678");
            String customerId = id(created);
            String first = addressId(created, 0);

            String afterAdd = body(post("/api/v1/customers/{id}/addresses", customerId)
                    .contentType(MediaType.APPLICATION_JSON).content(addressJson("Shop 7, GEC", true)), 201);
            String second = addressId(afterAdd, 1);
            assertThat((List<Boolean>) JsonPath.read(afterAdd, "$.addresses[*].isDefault")).containsExactly(false, true);
            assertThat((Integer) JsonPath.read(afterAdd, "$.version")).isZero(); // addresses do not bump version

            mockMvc.perform(put("/api/v1/customers/{id}/addresses/{a}", customerId, first).header("Authorization", owner.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"label\":\"Office\",\"line\":\"Level 4, Gulshan\",\"district\":\"Dhaka\",\"zone\":\"INSIDE_DHAKA\",\"isDefault\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.addresses[0].label").value("Office"))
                    .andExpect(jsonPath("$.addresses[0].addressId").value(first))
                    .andExpect(jsonPath("$.addresses[0].area").doesNotExist())
                    .andExpect(jsonPath("$.addresses[1].isDefault").value(true)); // false keeps the current default

            mockMvc.perform(delete("/api/v1/customers/{id}/addresses/{a}", customerId, second).header("Authorization", owner.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.addresses.length()").value(1))
                    .andExpect(jsonPath("$.addresses[0].isDefault").value(true)); // oldest remaining became default

            for (int i = 0; i < 4; i++) {
                mockMvc.perform(post("/api/v1/customers/{id}/addresses", customerId).header("Authorization", owner.bearer())
                                .contentType(MediaType.APPLICATION_JSON).content(addressJson("Extra " + i, false)))
                        .andExpect(status().isCreated());
            }
            mockMvc.perform(post("/api/v1/customers/{id}/addresses", customerId).header("Authorization", owner.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content(addressJson("Sixth", false)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ADDRESS_LIMIT_REACHED"));
        }

        @Test
        void parallelAddressAddsAreNotLost() throws Exception {
            String customerId = id(api.create(owner.bearer(), "{\"name\":\"No Address\",\"phone\":\"01712345678\"}")
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

            List<Integer> statuses = runInParallel(4, i -> mockMvc.perform(post("/api/v1/customers/{id}/addresses", customerId)
                            .header("Authorization", owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                            .content(addressJson("Parallel " + i, false)))
                    .andReturn().getResponse().getStatus());

            assertThat(statuses).containsOnly(201);
            Document stored = mongoTemplate.findById(new ObjectId(customerId), Document.class, "customers");
            List<Document> addresses = stored.getList("addresses", Document.class);
            assertThat(addresses).hasSize(4);
            assertThat(addresses).filteredOn(a -> a.getBoolean("isDefault")).hasSize(1);
        }
    }

    // ==== Java API for Order =================================================================

    @Nested
    class OrderApi {

        private final AddressDto delivery = new AddressDto("House 9, Road 2", "Uttara", "Dhaka", DeliveryZone.INSIDE_DHAKA, null);

        @Test
        void findOrCreateCreatesOnceThenReusesAndAddsNewAddressOnly() throws Exception {
            OrderCustomer created = asOwner(() -> customerService.findOrCreateByPhone(
                    new NewOrderCustomer("Karim", "+8801712345678", delivery)));
            assertThat(created.phone()).isEqualTo("01712345678");
            assertThat(created.defaultAddress().line()).isEqualTo("House 9, Road 2");

            OrderCustomer again = asOwner(() -> customerService.findOrCreateByPhone(new NewOrderCustomer("Someone Else",
                    "01712345678", new AddressDto("house 9,  road 2", "UTTARA", "dhaka", DeliveryZone.INSIDE_DHAKA, null))));
            assertThat(again.customerId()).isEqualTo(created.customerId());
            assertThat(again.name()).isEqualTo("Karim");

            asOwner(() -> customerService.findOrCreateByPhone(new NewOrderCustomer("X", "01712345678",
                    new AddressDto("Shop 3", null, "Sylhet", DeliveryZone.OUTSIDE_DHAKA, null))));
            Document stored = mongoTemplate.findById(new ObjectId(created.customerId()), Document.class, "customers");
            assertThat(stored.getList("addresses", Document.class)).hasSize(2);
            assertThat(stored.getString("name")).isEqualTo("Karim");
            assertThat(mongoTemplate.count(new Query(), "customers")).isOne();
        }

        @Test
        void archivedCustomerIsReactivatedByAnOrder() throws Exception {
            String customerId = id(api.createOk(owner.bearer(), "Rahima", "01712345678"));
            mockMvc.perform(patch("/api/v1/customers/{id}/status", customerId).header("Authorization", owner.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ARCHIVED\",\"version\":0}"))
                    .andExpect(status().isOk());

            OrderCustomer found = asOwner(() -> customerService.findOrCreateByPhone(new NewOrderCustomer("R", "01712345678", null)));

            assertThat(found.customerId()).isEqualTo(customerId);
            assertThat(found.status().name()).isEqualTo("ACTIVE");
        }

        @Test
        void parallelFindOrCreateOfANewPhoneMakesOneCustomer() throws Exception {
            List<String> ids = runInParallel(5, i -> asOwner(() -> transactionRunner.run(() ->
                    customerService.findOrCreateByPhone(new NewOrderCustomer("Racer " + i, "01712345678", delivery)).customerId())));
            assertThat(ids).containsOnly(ids.get(0));
            assertThat(mongoTemplate.count(new Query(), "customers")).isOne();
        }

        @Test
        void countersChangeOnlyInsideATransactionAndKeepTheVersion() throws Exception {
            String customerId = id(api.createOk(owner.bearer(), "Rahima", "01712345678"));
            Instant first = Instant.parse("2026-11-01T10:00:00Z");
            Instant later = Instant.parse("2026-11-20T10:00:00Z");

            asOwner(() -> transactionRunner.run(() -> {
                customerService.recordOrderCreated(customerId, later);
                customerService.recordOrderCreated(customerId, first); // older: lastOrderAt stays "later"
                customerService.recordDelivered(customerId, 45_050L);
                customerService.recordReturned(customerId);
                customerService.recordCancelled(customerId);
                return null;
            }));

            mockMvc.perform(get("/api/v1/customers/{id}", customerId).header("Authorization", owner.bearer()))
                    .andExpect(jsonPath("$.stats.totalOrders").value(2))
                    .andExpect(jsonPath("$.stats.deliveredOrders").value(1))
                    .andExpect(jsonPath("$.stats.returnedOrders").value(1))
                    .andExpect(jsonPath("$.stats.cancelledOrders").value(1))
                    .andExpect(jsonPath("$.stats.totalSpent").value(45_050))
                    .andExpect(jsonPath("$.stats.lastOrderAt").value("2026-11-20T10:00:00Z"))
                    .andExpect(jsonPath("$.version").value(0));
            // An edit loaded before the orders still saves (counters never touch the version)...
            mockMvc.perform(put("/api/v1/customers/{id}", customerId).header("Authorization", owner.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Rahima\",\"phone\":\"01712345678\",\"version\":0}"))
                    .andExpect(status().isOk())
                    // ...and never writes old counters back.
                    .andExpect(jsonPath("$.stats.totalOrders").value(2));

            assertThatThrownBy(() -> asOwner(() -> {
                customerService.recordReturned(customerId);
                return null;
            })).isInstanceOf(IllegalTransactionStateException.class);
        }
    }

    // ==== Helpers ==========================================================================

    private interface Task<T> {
        T run(int index) throws Exception;
    }

    private static <T> List<T> runInParallel(int count, Task<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.run(index);
                }));
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

    /** Runs service code as the owner, the way the JWT filter sets up a request, then cleans up. */
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

    private String body(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        return mockMvc.perform(request.header("Authorization", owner.bearer()))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    private void expectNames(String url, String... names) throws Exception {
        String json = body(get(url), 200);
        List<String> actual = JsonPath.read(json, "$.content[*].name");
        assertThat(actual).as(url).containsExactly(names);
    }
}

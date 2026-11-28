package com.sellerdesk.courier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import com.sellerdesk.courier.provider.mock.MockCourierProvider;
import com.sellerdesk.testsupport.MongoIntegrationTestBase;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The HTTP layer of {@code ShipmentController}: permissions (403), unknown ids (404), business
 * conflicts (409) and validation (400).
 */
class ShipmentControllerTest extends MongoIntegrationTestBase {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    private MockCourierProvider mockCourier;

    private TestOwner owner;
    private String orderId;
    private String shipmentId;

    @BeforeEach
    void setUp() throws Exception {
        mockCourier.reset();
        owner = createVerifiedOwner("ship-http");
        CourierTestApi api = new CourierTestApi(mockMvc);
        orderId = api.packedOrder(owner.bearer(), "SH-1")[1];
        shipmentId = CourierTestApi.id(api.sendOk(owner.bearer(), orderId));
    }

    private Map<Permission, List<Supplier<MockHttpServletRequestBuilder>>> endpoints() {
        return Map.of(
                Permission.ORDER_FULFILL, List.of(
                        () -> CourierTestApi.sendRequest(orderId, "MOCK"),
                        () -> CourierTestApi.cancelRequest(shipmentId),
                        () -> post("/api/v1/shipments/bulk").contentType(JSON)
                                .content("{\"provider\":\"MOCK\",\"orderIds\":[\"%s\"]}".formatted(orderId))),
                Permission.ORDER_VIEW, List.of(
                        () -> get("/api/v1/shipments/{id}", shipmentId),
                        () -> get("/api/v1/shipments"),
                        () -> get("/api/v1/orders/{id}/shipments", orderId),
                        () -> post("/api/v1/shipments/{id}/track", shipmentId)));
    }

    @Test
    void everyEndpointIs403WithoutItsPermissionAndNothingChanges() throws Exception {
        Document before = shipment();
        for (var entry : endpoints().entrySet()) {
            EnumSet<Permission> allButOne = EnumSet.allOf(Permission.class);
            allButOne.remove(entry.getKey());
            String bearer = bearerForStaff(owner.shopId(), Role.MODERATOR, allButOne);
            for (Supplier<MockHttpServletRequestBuilder> request : entry.getValue()) {
                mockMvc.perform(request.get().header("Authorization", bearer))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            }
        }
        assertThat(shipment()).isEqualTo(before);
        assertThat(mongoTemplate.count(new Query(), "shipments")).isOne();
    }

    @Test
    void defaultRoles() throws Exception {
        // PACKER packs and ships: may send and cancel. MODERATOR has no ORDER_FULFILL. ACCOUNTANT can view and track.
        String packer = bearerForStaff(owner.shopId(), Role.PACKER);
        String moderator = bearerForStaff(owner.shopId(), Role.MODERATOR);
        String accountant = bearerForStaff(owner.shopId(), Role.ACCOUNTANT);

        mockMvc.perform(CourierTestApi.sendRequest(orderId, "MOCK").header("Authorization", moderator)).andExpect(status().isForbidden());
        mockMvc.perform(CourierTestApi.cancelRequest(shipmentId).header("Authorization", accountant)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/shipments/{id}/track", shipmentId).header("Authorization", accountant)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/shipments/{id}", shipmentId).header("Authorization", moderator)).andExpect(status().isOk());
        mockMvc.perform(CourierTestApi.sendRequest(orderId, "MOCK").header("Authorization", packer)).andExpect(status().isOk());
        mockMvc.perform(CourierTestApi.cancelRequest(shipmentId).header("Authorization", packer)).andExpect(status().isOk());
    }

    @Test
    void platformAdminIs403AndNoTokenIs401() throws Exception {
        String admin = bearerForStaff(null, Role.PLATFORM_ADMIN, Set.of());
        mockMvc.perform(get("/api/v1/shipments/{id}", shipmentId).header("Authorization", admin)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/shipments/{id}", shipmentId)).andExpect(status().isUnauthorized());
        mockMvc.perform(CourierTestApi.sendRequest(orderId, "MOCK")).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownOrMalformedIdsAre404() throws Exception {
        String unknown = new ObjectId().toHexString();
        for (MockHttpServletRequestBuilder request : List.of(
                get("/api/v1/shipments/{id}", unknown), get("/api/v1/shipments/{id}", "not-an-id"),
                post("/api/v1/shipments/{id}/track", unknown), CourierTestApi.cancelRequest(unknown),
                get("/api/v1/orders/{id}/shipments", unknown), CourierTestApi.sendRequest(unknown, "MOCK"),
                CourierTestApi.sendRequest("not-an-id", "MOCK"))) {
            mockMvc.perform(request.header("Authorization", owner.bearer()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Test
    void conflicts() throws Exception {
        // Cancel twice is harmless; after pickup it is a 409.
        mockMvc.perform(CourierTestApi.cancelRequest(shipmentId).header("Authorization", owner.bearer())).andExpect(status().isOk());
        mockMvc.perform(CourierTestApi.cancelRequest(shipmentId).header("Authorization", owner.bearer())).andExpect(status().isOk());
        String[] other = new CourierTestApi(mockMvc).packedOrder(owner.bearer(), "SH-2");
        String booked = CourierTestApi.id(new CourierTestApi(mockMvc).sendOk(owner.bearer(), other[1]));
        new CourierTestApi(mockMvc).webhook(CourierTestApi.consignmentId(new CourierTestApi(mockMvc).fetch(owner.bearer(), booked)),
                "picked_up");
        mockMvc.perform(CourierTestApi.cancelRequest(booked).header("Authorization", owner.bearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIPMENT_NOT_CANCELLABLE"));
        // The order is SHIPPED now: sending it again is ORDER_NOT_PACKED.
        mockMvc.perform(CourierTestApi.sendRequest(other[1], "MOCK").header("Authorization", owner.bearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_PACKED"));
    }

    @Test
    void validation() throws Exception {
        String bearer = owner.bearer();
        expect400(post("/api/v1/orders/{id}/shipment", orderId).contentType(JSON).content("{}"), "VALIDATION_FAILED", bearer);
        expect400(post("/api/v1/orders/{id}/shipment", orderId).contentType(JSON).content("{\"provider\":\"PIGEON\"}"),
                "MALFORMED_REQUEST", bearer);
        expect400(post("/api/v1/orders/{id}/shipment", orderId).contentType(JSON)
                .content("{\"provider\":\"MOCK\",\"note\":\"%s\"}".formatted("x".repeat(201))), "VALIDATION_FAILED", bearer);
        expect400(post("/api/v1/shipments/{id}/cancel", shipmentId).contentType(JSON).content("{\"reason\":\" \"}"),
                "VALIDATION_FAILED", bearer);
        expect400(post("/api/v1/shipments/bulk").contentType(JSON).content("{\"provider\":\"MOCK\",\"orderIds\":[]}"),
                "VALIDATION_FAILED", bearer);
        String ids21 = String.join(",", java.util.Collections.nCopies(21, "\"" + orderId + "\""));
        expect400(post("/api/v1/shipments/bulk").contentType(JSON).content("{\"provider\":\"MOCK\",\"orderIds\":[" + ids21 + "]}"),
                "VALIDATION_FAILED", bearer);
        expect400(post("/api/v1/shipments/bulk").contentType(JSON)
                .content("{\"provider\":\"MOCK\",\"orderIds\":[\"%s\",\"%s\"]}".formatted(orderId, orderId)), "VALIDATION_FAILED", bearer);
        expect400(get("/api/v1/shipments?status=LOST"), "VALIDATION_FAILED", bearer);
        expect400(get("/api/v1/shipments?size=101"), "VALIDATION_FAILED", bearer);
    }

    private void expect400(MockHttpServletRequestBuilder request, String code, String bearer) throws Exception {
        mockMvc.perform(request.header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(code));
    }

    private Document shipment() {
        return mongoTemplate.findById(new ObjectId(shipmentId), Document.class, "shipments");
    }
}

package com.sellerdesk.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import com.sellerdesk.testsupport.MongoIntegrationTestBase;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * {@code @PreAuthorize("hasAuthority('STAFF_MANAGE')")} on every staff endpoint. Missing permission
 * → {@code 403 FORBIDDEN} and nothing changes. With STAFF_MANAGE but not owner: only own
 * permissions can be granted ({@code 403 PERMISSION_NOT_GRANTABLE}).
 */
class StaffPermissionTest extends MongoIntegrationTestBase {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private StaffTestApi api;
    private TestOwner owner;
    private StaffTestApi.Member packer;
    private String invitationId;

    @BeforeEach
    void setUp() throws Exception {
        api = new StaffTestApi(mockMvc, mailSender);
        owner = createVerifiedOwner("staffperm");
        packer = api.inviteAndAccept(owner.bearer(), uniqueEmail("packer"), "PACKER");
        invitationId = api.invite(owner.bearer(), uniqueEmail("pending"), "ACCOUNTANT");
    }

    /** Every staff management endpoint, with valid input, so only the permission can fail. */
    private List<AbstractMockHttpServletRequestBuilder<?>> allEndpoints() {
        return List.of(
                get("/api/v1/staff"),
                get("/api/v1/staff/permissions"),
                get("/api/v1/staff/{id}", packer.userId()),
                patch("/api/v1/staff/{id}", packer.userId()).contentType(JSON).content("{\"role\":\"MODERATOR\"}"),
                patch("/api/v1/staff/{id}/status", packer.userId()).contentType(JSON).content("{\"status\":\"DISABLED\"}"),
                post("/api/v1/staff/invitations").contentType(JSON)
                        .content("{\"email\":\"%s\",\"role\":\"PACKER\"}".formatted(uniqueEmail("new"))),
                post("/api/v1/staff/invitations/{id}/resend", invitationId),
                delete("/api/v1/staff/invitations/{id}", invitationId));
    }

    private void expectForbiddenEverywhere(String bearer) throws Exception {
        Document packerBefore = userDoc();
        Document invitationBefore = invitationDoc();
        long invitationsBefore = mongoTemplate.getCollection("staff_invitations").countDocuments();

        for (AbstractMockHttpServletRequestBuilder<?> request : allEndpoints()) {
            mockMvc.perform(request.header("Authorization", bearer))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        assertThat(userDoc()).isEqualTo(packerBefore);
        assertThat(invitationDoc()).isEqualTo(invitationBefore);
        assertThat(mongoTemplate.getCollection("staff_invitations").countDocuments()).isEqualTo(invitationsBefore);
    }

    @Test
    void everyRoleWithoutStaffManageGets403() throws Exception {
        expectForbiddenEverywhere(packer.bearer());
        expectForbiddenEverywhere(bearerForStaff(owner.shopId(), Role.MODERATOR));
        expectForbiddenEverywhere(bearerForStaff(owner.shopId(), Role.ACCOUNTANT));
        // Every permission except STAFF_MANAGE is still not enough.
        expectForbiddenEverywhere(bearerForStaff(owner.shopId(), Role.MODERATOR, EnumSet.complementOf(EnumSet.of(Permission.STAFF_MANAGE))));
    }

    @Test
    void platformAdminGets403() throws Exception {
        expectForbiddenEverywhere(bearerForStaff(null, Role.PLATFORM_ADMIN, Set.of()));
    }

    @Test
    void anonymousGets401() throws Exception {
        for (AbstractMockHttpServletRequestBuilder<?> request : allEndpoints()) {
            mockMvc.perform(request).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void managerWithStaffManageCanManageButOnlyGrantOwnPermissions() throws Exception {
        String manager = bearerForStaff(owner.shopId(), Role.MODERATOR, EnumSet.of(Permission.STAFF_MANAGE, Permission.ORDER_VIEW));

        mockMvc.perform(get("/api/v1/staff").header("Authorization", manager)).andExpect(status().isOk());

        // PACKER defaults include ORDER_FULFILL etc., which the manager does not have.
        mockMvc.perform(post("/api/v1/staff/invitations").header("Authorization", manager).contentType(JSON)
                        .content("{\"email\":\"%s\",\"role\":\"PACKER\"}".formatted(uniqueEmail("x"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_NOT_GRANTABLE"));
        mockMvc.perform(post("/api/v1/staff/invitations").header("Authorization", manager).contentType(JSON)
                        .content("{\"email\":\"%s\",\"role\":\"PACKER\",\"permissions\":[\"ORDER_VIEW\"]}".formatted(uniqueEmail("y"))))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/api/v1/staff/{id}", packer.userId()).header("Authorization", manager).contentType(JSON)
                        .content("{\"permissions\":[\"ORDER_VIEW\",\"REPORT_VIEW\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_NOT_GRANTABLE"));
        // Removing permissions the manager lacks (ORDER_FULFILL...) is fine.
        mockMvc.perform(patch("/api/v1/staff/{id}", packer.userId()).header("Authorization", manager).contentType(JSON)
                        .content("{\"permissions\":[\"ORDER_VIEW\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[0]").value("ORDER_VIEW"));
        mockMvc.perform(patch("/api/v1/staff/{id}/status", packer.userId()).header("Authorization", manager).contentType(JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());
    }

    private Document userDoc() {
        return mongoTemplate.findById(new org.bson.types.ObjectId(packer.userId()), Document.class, "users");
    }

    private Document invitationDoc() {
        return mongoTemplate.findById(new org.bson.types.ObjectId(invitationId), Document.class, "staff_invitations");
    }
}

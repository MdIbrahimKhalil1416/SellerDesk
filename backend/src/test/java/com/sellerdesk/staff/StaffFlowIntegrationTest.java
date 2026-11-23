package com.sellerdesk.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sellerdesk.testsupport.MongoIntegrationTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The staff module end to end against a real MongoDB: invite → email → preview → accept → login,
 * and what role changes and deactivation do to existing sessions.
 */
class StaffFlowIntegrationTest extends MongoIntegrationTestBase {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private StaffTestApi api;
    private TestOwner owner;

    @BeforeEach
    void setUp() throws Exception {
        api = new StaffTestApi(mockMvc, mailSender);
        owner = createVerifiedOwner("staff");
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private ResultActions preview(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/invitations/preview").contentType(JSON)
                .content("{\"token\":\"%s\"}".formatted(token)));
    }

    private Document invitationDoc(String id) {
        return mongoTemplate.findById(new ObjectId(id), Document.class, "staff_invitations");
    }

    private Document userDoc(String id) {
        return mongoTemplate.findById(new ObjectId(id), Document.class, "users");
    }

    /** Moves an invitation's send history back in time (cooldown / expiry tests). */
    private void ageInvitation(String id, Duration by) {
        Document doc = invitationDoc(id);
        Instant lastSent = doc.getDate("lastSentAt").toInstant().minus(by);
        Instant expires = doc.getDate("expiresAt").toInstant().minus(by);
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(new ObjectId(id))),
                new Update().set("lastSentAt", Date.from(lastSent)).set("expiresAt", Date.from(expires))
                        .set("recentSends", List.of(Date.from(lastSent))),
                "staff_invitations");
    }

    // ==== invite → accept → login =========================================================

    @Test
    void inviteAcceptLoginFlow() throws Exception {
        String email = uniqueEmail("karim");
        String invitationId = api.invite(owner.bearer(), email, "PACKER");
        String token = api.awaitInviteToken(email);

        // The database has only the hash of the token.
        Document invitation = invitationDoc(invitationId);
        assertThat(invitation.getString("status")).isEqualTo("PENDING");
        assertThat(invitation.getString("tokenHash")).hasSize(64).isNotEqualTo(token);
        assertThat(invitation.toJson()).doesNotContain(token);

        preview(token).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.shopName").value(owner.shopName()))
                .andExpect(jsonPath("$.role").value("PACKER"))
                .andExpect(jsonPath("$.inviterName").value("staff Owner"));

        MvcResult accepted = api.accept(token, "Karim Hossain");
        String body = accepted.getResponse().getContentAsString();
        assertThat(accepted.getResponse().getCookie("sd_refresh")).isNotNull();
        String userId = JsonPath.read(body, "$.user.id");
        assertThat((String) JsonPath.read(body, "$.user.status")).isEqualTo("ACTIVE");

        Document user = userDoc(userId);
        assertThat(user.get("shopId")).isEqualTo(new ObjectId(owner.shopId()));
        assertThat(user.getString("role")).isEqualTo("PACKER");
        assertThat(user.getString("status")).isEqualTo("ACTIVE");
        assertThat(user.getString("name")).isEqualTo("Karim Hossain");
        assertThat(user.get("invitedBy")).isEqualTo(new ObjectId(owner.userId()));
        assertThat(user.getList("permissions", String.class))
                .containsExactlyInAnyOrder("PRODUCT_VIEW", "STOCK_VIEW", "ORDER_VIEW", "ORDER_FULFILL");
        assertThat(invitationDoc(invitationId).getString("status")).isEqualTo("ACCEPTED");
        assertThat(invitationDoc(invitationId).get("acceptedUserId")).isEqualTo(new ObjectId(userId));

        // The token from accept works, and later logins with the chosen password work.
        String bearer = "Bearer " + JsonPath.read(body, "$.accessToken");
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("PACKER"))
                .andExpect(jsonPath("$.shop.id").value(owner.shopId()));
        login(email, StaffTestApi.STAFF_PASSWORD).andExpect(status().isOk());

        // A used link is dead.
        mockMvc.perform(post("/api/v1/auth/invitations/accept").contentType(JSON)
                        .content("{\"token\":\"%s\",\"name\":\"X\",\"password\":\"other2026x\"}".formatted(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INVITATION"));

        // The list now shows the member, not the invitation.
        mockMvc.perform(get("/api/v1/staff").header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].role").value("SHOP_OWNER"))
                .andExpect(jsonPath("$.content[1].id").value(userId))
                .andExpect(jsonPath("$.content[1].kind").value("MEMBER"));
    }

    @Test
    void acceptValidatesPasswordRules() throws Exception {
        String email = uniqueEmail("weak");
        api.invite(owner.bearer(), email, "PACKER");
        String token = api.awaitInviteToken(email);

        mockMvc.perform(post("/api/v1/auth/invitations/accept").contentType(JSON)
                        .content("{\"token\":\"%s\",\"name\":\"W\",\"password\":\"short\"}".formatted(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/v1/auth/invitations/accept").contentType(JSON)
                        .content("{\"token\":\"%s\",\"name\":\"W\",\"password\":\"%s\"}".formatted(token, email)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
        preview(token).andExpect(status().isOk()); // still usable
    }

    @Test
    void expiredLinkSaysExpired() throws Exception {
        String email = uniqueEmail("late");
        String id = api.invite(owner.bearer(), email, "PACKER");
        String token = api.awaitInviteToken(email);
        ageInvitation(id, Duration.ofDays(8));

        preview(token).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVITATION_EXPIRED"));
    }

    // ==== invite errors ====================================================================

    @Test
    void inviteConflictsAndValidation() throws Exception {
        String email = uniqueEmail("dup");
        api.invite(owner.bearer(), email, "PACKER");

        invite(owner.bearer(), "{\"email\":\"%s\",\"role\":\"PACKER\"}".formatted(email.toUpperCase()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_INVITED"));
        invite(owner.bearer(), "{\"email\":\"%s\",\"role\":\"PACKER\"}".formatted(owner.email()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_A_MEMBER"));
        TestOwner other = createVerifiedOwner("elsewhere");
        invite(owner.bearer(), "{\"email\":\"%s\",\"role\":\"PACKER\"}".formatted(other.email()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));

        invite(owner.bearer(), "{\"email\":\"x@example.com\",\"role\":\"SHOP_OWNER\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("role"));
        invite(owner.bearer(), "{\"email\":\"x@example.com\",\"role\":\"PACKER\",\"permissions\":[\"GOD_MODE\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("permissions"));
    }

    @Test
    void expiredInvitationIsReusedOnInvite() throws Exception {
        String email = uniqueEmail("again");
        String id = api.invite(owner.bearer(), email, "PACKER");
        String oldToken = api.awaitInviteToken(email);
        ageInvitation(id, Duration.ofDays(8));

        invite(owner.bearer(), "{\"email\":\"%s\",\"role\":\"ACCOUNTANT\"}".formatted(email))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.role").value("ACCOUNTANT"));
        String newToken = api.awaitInviteToken(email, oldToken);
        preview(oldToken).andExpect(jsonPath("$.code").value("INVALID_INVITATION"));
        preview(newToken).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ACCOUNTANT"));
    }

    private ResultActions invite(String bearer, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/invitations").header("Authorization", bearer)
                .contentType(JSON).content(json));
    }

    // ==== resend / cancel ==================================================================

    @Test
    void resendRotatesTheLinkAndIsRateLimited() throws Exception {
        String email = uniqueEmail("resend");
        String id = api.invite(owner.bearer(), email, "MODERATOR");
        String firstToken = api.awaitInviteToken(email);

        mockMvc.perform(post("/api/v1/staff/invitations/{id}/resend", id).header("Authorization", owner.bearer()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("INVITE_RESEND_TOO_SOON"))
                .andExpect(header().exists("Retry-After"));

        ageInvitation(id, Duration.ofMinutes(2));
        mockMvc.perform(post("/api/v1/staff/invitations/{id}/resend", id).header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        String secondToken = api.awaitInviteToken(email, firstToken);

        preview(firstToken).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INVITATION"));
        preview(secondToken).andExpect(status().isOk());
        assertThat(invitationDoc(id).getInteger("sendCount")).isEqualTo(2);
    }

    @Test
    void cancelKillsTheLink() throws Exception {
        String email = uniqueEmail("cancel");
        String id = api.invite(owner.bearer(), email, "PACKER");
        String token = api.awaitInviteToken(email);

        mockMvc.perform(delete("/api/v1/staff/invitations/{id}", id).header("Authorization", owner.bearer()))
                .andExpect(status().isNoContent());
        preview(token).andExpect(jsonPath("$.code").value("INVALID_INVITATION"));
        mockMvc.perform(delete("/api/v1/staff/invitations/{id}", id).header("Authorization", owner.bearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVITATION_NOT_PENDING"));
        mockMvc.perform(post("/api/v1/staff/invitations/{id}/resend", id).header("Authorization", owner.bearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVITATION_NOT_PENDING"));

        // Cancelled invitations are not listed, and the email can be invited again.
        mockMvc.perform(get("/api/v1/staff").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.totalElements").value(1));
        api.invite(owner.bearer(), email, "PACKER");
    }

    // ==== member changes ===================================================================

    @Test
    void roleChangeEndsSessionAndAppliesNewRights() throws Exception {
        StaffTestApi.Member member = api.inviteAndAccept(owner.bearer(), uniqueEmail("promo"), "PACKER");

        mockMvc.perform(patch("/api/v1/staff/{id}", member.userId()).header("Authorization", owner.bearer())
                        .contentType(JSON).content("{\"role\":\"MODERATOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MODERATOR"))
                .andExpect(jsonPath("$.permissions.length()").value(10))
                .andExpect(jsonPath("$.invitedBy.id").value(owner.userId()));

        // Old token is revoked at once (tokenVersion bumped + cache evicted).
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", member.bearer()))
                .andExpect(status().isUnauthorized());
        String fresh = JsonPath.read(login(member.email(), StaffTestApi.STAFF_PASSWORD)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + fresh))
                .andExpect(jsonPath("$.user.role").value("MODERATOR"))
                .andExpect(jsonPath("$.user.permissions[?(@ == 'ORDER_CREATE')]").exists());
    }

    @Test
    void sameValuesDoNotEndTheSession() throws Exception {
        StaffTestApi.Member member = api.inviteAndAccept(owner.bearer(), uniqueEmail("same"), "PACKER");

        mockMvc.perform(patch("/api/v1/staff/{id}", member.userId()).header("Authorization", owner.bearer())
                        .contentType(JSON).content("{\"role\":\"PACKER\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/staff/{id}/status", member.userId()).header("Authorization", owner.bearer())
                        .contentType(JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", member.bearer())).andExpect(status().isOk());
    }

    @Test
    void deactivatedMemberCannotUseTokensLoginOrRefreshUntilReactivated() throws Exception {
        StaffTestApi.Member member = api.inviteAndAccept(owner.bearer(), uniqueEmail("off"), "ACCOUNTANT");

        mockMvc.perform(patch("/api/v1/staff/{id}/status", member.userId()).header("Authorization", owner.bearer())
                        .contentType(JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", member.bearer()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(member.refreshCookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        login(member.email(), StaffTestApi.STAFF_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mockMvc.perform(get("/api/v1/staff?status=DISABLED").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.content[0].id").value(member.userId()));

        mockMvc.perform(patch("/api/v1/staff/{id}/status", member.userId()).header("Authorization", owner.bearer())
                        .contentType(JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ACCOUNTANT"));
        login(member.email(), StaffTestApi.STAFF_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void ownerIsReadOnlyAndNobodyChangesThemselves() throws Exception {
        mockMvc.perform(patch("/api/v1/staff/{id}", owner.userId()).header("Authorization", owner.bearer())
                        .contentType(JSON).content("{\"role\":\"MODERATOR\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWNER_READ_ONLY"));
        mockMvc.perform(patch("/api/v1/staff/{id}/status", owner.userId()).header("Authorization", owner.bearer())
                        .contentType(JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWNER_READ_ONLY"));
        mockMvc.perform(get("/api/v1/staff/{id}", owner.userId()).header("Authorization", owner.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("SHOP_OWNER"));

        // A manager (STAFF_MANAGE) cannot touch the owner or themselves either.
        StaffTestApi.Member manager = api.inviteAndAccept(owner.bearer(), uniqueEmail("mgr"), "MODERATOR");
        mockMvc.perform(patch("/api/v1/staff/{id}", manager.userId()).header("Authorization", owner.bearer())
                        .contentType(JSON).content("{\"permissions\":[\"STAFF_MANAGE\",\"ORDER_VIEW\"]}"))
                .andExpect(status().isOk());
        String managerBearer = "Bearer " + JsonPath.read(login(manager.email(), StaffTestApi.STAFF_PASSWORD)
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
        mockMvc.perform(patch("/api/v1/staff/{id}/status", owner.userId()).header("Authorization", managerBearer)
                        .contentType(JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWNER_READ_ONLY"));
        mockMvc.perform(patch("/api/v1/staff/{id}", manager.userId()).header("Authorization", managerBearer)
                        .contentType(JSON).content("{\"permissions\":[\"STAFF_MANAGE\",\"ORDER_VIEW\",\"REPORT_VIEW\"]}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CANNOT_MODIFY_SELF"));
        mockMvc.perform(patch("/api/v1/staff/{id}/status", manager.userId()).header("Authorization", managerBearer)
                        .contentType(JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CANNOT_MODIFY_SELF"));
        assertThat(userDoc(owner.userId()).getString("status")).isEqualTo("ACTIVE");
    }

    // ==== list =============================================================================

    @Test
    void listOrderFiltersAndSearch() throws Exception {
        StaffTestApi.Member zed = api.inviteAndAccept(owner.bearer(), uniqueEmail("zed"), "PACKER");
        StaffTestApi.Member amy = api.inviteAndAccept(owner.bearer(), uniqueEmail("amy"), "ACCOUNTANT");
        mockMvc.perform(patch("/api/v1/staff/{id}/status", amy.userId()).header("Authorization", owner.bearer())
                .contentType(JSON).content("{\"status\":\"DISABLED\"}")).andExpect(status().isOk());
        String oldInvite = api.invite(owner.bearer(), uniqueEmail("old.invite"), "MODERATOR");
        ageInvitation(oldInvite, Duration.ofDays(8)); // expired
        String newInvite = api.invite(owner.bearer(), uniqueEmail("new.invite"), "PACKER");

        // owner, then invitations newest first, then members by name ("Member ACCOUNTANT" < "Member PACKER")
        mockMvc.perform(get("/api/v1/staff").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.content[0].id").value(owner.userId()))
                .andExpect(jsonPath("$.content[1].id").value(newInvite))
                .andExpect(jsonPath("$.content[1].status").value("INVITED"))
                .andExpect(jsonPath("$.content[1].kind").value("INVITATION"))
                .andExpect(jsonPath("$.content[2].id").value(oldInvite))
                .andExpect(jsonPath("$.content[2].status").value("EXPIRED"))
                .andExpect(jsonPath("$.content[3].id").value(amy.userId()))
                .andExpect(jsonPath("$.content[4].id").value(zed.userId()));

        expectIds("?status=INVITED", newInvite);
        expectIds("?status=EXPIRED", oldInvite);
        expectIds("?status=DISABLED", amy.userId());
        expectIds("?status=ACTIVE&role=PACKER", zed.userId());
        expectIds("?role=PACKER", newInvite, zed.userId());
        expectIds("?q=OLD.INVITE", oldInvite); // case-insensitive, "." is literal
        expectIds("?q=accountANT", amy.userId()); // name "Member ACCOUNTANT"
        expectIds("?q=.*", new String[0]); // regex characters are escaped

        mockMvc.perform(get("/api/v1/staff?size=2&page=1").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content[0].id").value(oldInvite));
        mockMvc.perform(get("/api/v1/staff?status=GONE").header("Authorization", owner.bearer()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private void expectIds(String query, String... ids) throws Exception {
        String body = mockMvc.perform(get("/api/v1/staff" + query).header("Authorization", owner.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> found = JsonPath.read(body, "$.content[*].id");
        assertThat(found).as(query).containsExactly(ids);
    }

    @Test
    void permissionCatalog() throws Exception {
        mockMvc.perform(get("/api/v1/staff/permissions").header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(16))
                .andExpect(jsonPath("$.permissions[0].code").value("SHOP_SETTINGS"))
                .andExpect(jsonPath("$.permissions[0].description").value("Edit shop profile and settings"))
                .andExpect(jsonPath("$.roleDefaults.PACKER.length()").value(4))
                .andExpect(jsonPath("$.roleDefaults.SHOP_OWNER").doesNotExist());
    }
}

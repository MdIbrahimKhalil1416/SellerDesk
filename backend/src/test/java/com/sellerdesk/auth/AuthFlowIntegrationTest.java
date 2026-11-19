package com.sellerdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sellerdesk.testsupport.MongoIntegrationTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The real thing: HTTP → security → services → MongoDB (real transactions, real unique/TTL
 * indexes). Mail is mocked by the base class, which lets the test read the code from the "sent" email.
 *
 * <p>Where MongoDB comes from (MONGODB_TEST_URI / Testcontainers / skip): see
 * {@link MongoIntegrationTestBase}. Every test uses unique emails and shop names.
 */
class AuthFlowIntegrationTest extends MongoIntegrationTestBase {

    private static final String APP_URL = "http://localhost:5173";

    @Test
    void registerThenVerifyThenLoginThenUseAndRefreshToken() throws Exception {
        String email = uniqueEmail("flow");
        String shopName = uniqueName("Flow Fashion");

        // 1. Register: 201, shop + PENDING owner exist in MongoDB.
        mockMvc.perform(post("/api/v1/auth/register-shop").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email, shopName)))
                .andExpect(status().isCreated());

        assertThat(count("users", Criteria.where("email").is(email).and("status").is("PENDING_VERIFICATION"))).isOne();
        assertThat(count("shops", Criteria.where("name").is(shopName))).isOne();

        // 2. Login before verification: correct password, but not verified yet.
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, "flowShop2026")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

        // 3. The code was emailed in the background (after commit, @Async). Read it from the mock.
        String code = awaitEmailedCode(email);
        assertThat(count("otp_tokens", Criteria.where("email").is(email).and("codeHash").ne(code))).isOne();

        // 4. Wrong code first: rejected, attempt counted.
        mockMvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content(verifyJson(email, code.equals("000000") ? "111111" : "000000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OTP"));

        // 5. Right code: ACTIVE, access token in body, refresh token in cookie.
        mockMvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content(verifyJson(email, code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.status").value("ACTIVE"))
                .andExpect(jsonPath("$.user.role").value("SHOP_OWNER"));

        // 6. Same code again: single use.
        mockMvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content(verifyJson(email, code)))
                .andExpect(status().isBadRequest());

        // 7. Login with password.
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, "flowShop2026")))
                .andExpect(status().isOk())
                .andReturn();
        String accessToken = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
        Cookie refreshCookie = login.getResponse().getCookie("sd_refresh");
        assertThat(refreshCookie).isNotNull();
        assertThat(refreshCookie.isHttpOnly()).isTrue();

        // 8. Protected endpoint with the access token: my own shop.
        mockMvc.perform(get("/api/v1/shop").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(shopName))
                .andExpect(jsonPath("$.phone").value("01712345678"))
                .andExpect(jsonPath("$.settings.deliveryChargeInsideDhaka").value(6000));

        // 9. Refresh with the cookie from the app's origin.
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie).header("Origin", APP_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        // 10. Wrong password.
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, "wrongPass99")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        // 11. Me: user + shop summary.
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.shop.name").value(shopName));

        // 12. Logout: 204, cookie deleted, tokenVersion bumped in MongoDB.
        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());
        assertThat(count("users", Criteria.where("email").is(email).and("tokenVersion").is(1))).isOne();

        // 13. Old access and refresh tokens are dead immediately (cache was evicted).
        mockMvc.perform(get("/api/v1/shop").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie).header("Origin", APP_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void forgotThenResetPasswordThenLoginWithNewPassword() throws Exception {
        String email = uniqueEmail("reset");
        mockMvc.perform(post("/api/v1/auth/register-shop").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email, uniqueName("Reset Shop"))))
                .andExpect(status().isCreated());
        awaitEmailedCode(email); // verification mail; the account stays PENDING on purpose
        clearInvocations(mailSender);

        // Forgot: 202. A pending owner may reset too (the code proves the email).
        mockMvc.perform(post("/api/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
        String code = awaitEmailedCode(email);

        mockMvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"code\":\"%s\",\"newPassword\":\"brandNew2026\"}".formatted(email, code)))
                .andExpect(status().isNoContent());

        assertThat(count("users", Criteria.where("email").is(email)
                .and("status").is("ACTIVE").and("tokenVersion").is(1))).isOne();

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, "flowShop2026")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, "brandNew2026")))
                .andExpect(status().isOk());
    }

    @Test
    void resendAndForgotLookTheSameForUnknownAndRateLimitedEmails() throws Exception {
        String email = uniqueEmail("pending");
        mockMvc.perform(post("/api/v1/auth/register-shop").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email, uniqueName("Pending Shop"))))
                .andExpect(status().isCreated());

        // Within the 60 s cooldown (a code was just sent) vs. an email nobody registered.
        String pendingBody = mockMvc.perform(post("/api/v1/auth/verify-email/resend")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String unknownBody = mockMvc.perform(post("/api/v1/auth/verify-email/resend")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + uniqueEmail("ghost") + "\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        assertThat(pendingBody).isEqualTo(unknownBody);
    }

    @Test
    void duplicateEmailIsRejectedAndLeavesNoExtraShop() throws Exception {
        String email = uniqueEmail("dup");
        String secondShop = uniqueName("Second Shop");
        mockMvc.perform(post("/api/v1/auth/register-shop").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email, uniqueName("First Shop"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register-shop").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email.toUpperCase(), secondShop)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));

        assertThat(count("shops", Criteria.where("name").is(secondShop))).isZero();
    }

    @Test
    void shopEndpointWithoutTokenIs401() throws Exception {
        mockMvc.perform(get("/api/v1/shop"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // ---- helpers ------------------------------------------------------------------------

    private long count(String collection, Criteria criteria) {
        return mongoTemplate.count(new Query(criteria), collection);
    }

    private static String registerJson(String email, String shopName) {
        return """
                {"ownerName":"Flow Owner","email":"%s","password":"flowShop2026",
                 "shopName":"%s","shopPhone":"+8801712345678",
                 "address":{"line":"House 1","district":"Dhaka","zone":"INSIDE_DHAKA"}}
                """.formatted(email, shopName);
    }

    private static String loginJson(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    }

    private static String verifyJson(String email, String code) {
        return "{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code);
    }
}

package com.sellerdesk.auth.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sellerdesk.auth.dto.MeResponse;
import com.sellerdesk.auth.dto.OtpSentResponse;
import com.sellerdesk.auth.dto.RegisterShopResponse;
import com.sellerdesk.auth.dto.UserResponse;
import com.sellerdesk.auth.model.UserStatus;
import com.sellerdesk.auth.service.AuthService;
import com.sellerdesk.common.security.AppUserDetails;
import com.sellerdesk.common.security.AppUserDetailsService;
import com.sellerdesk.common.security.JwtService;
import com.sellerdesk.common.security.Role;
import com.sellerdesk.shop.dto.ShopSummary;
import com.sellerdesk.shop.model.ShopStatus;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP layer only: security rules, validation messages, status codes, cookie attributes.
 * AuthService is mocked, so no database is needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    /** Mocked so the JWT filter can "find" the user behind a test token without a database. */
    @MockitoBean
    private AppUserDetailsService userDetailsService;

    @Autowired
    private JwtService jwtService;

    // ---- logout / me (need a Bearer token) ----------------------------------------------

    @Test
    void logoutWithoutTokenIs401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(authService);
    }

    @Test
    void logoutRevokesAndDeletesTheCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", allOf(
                        containsString("sd_refresh=;"),
                        containsString("Max-Age=0"),
                        containsString("Path=/api/v1/auth"),
                        containsString("HttpOnly"))));
        verify(authService).logout();
    }

    @Test
    void meWithoutTokenIs401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsUserAndShop() throws Exception {
        when(authService.me()).thenReturn(new MeResponse(
                new UserResponse("u1", "Rahim", "rahim@example.com", null, Role.SHOP_OWNER, List.of(), UserStatus.ACTIVE),
                new ShopSummary("shopA", "Rahim Fashion", ShopStatus.ACTIVE)));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value("u1"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.shop.name").value("Rahim Fashion"))
                .andExpect(jsonPath("$.shop.status").value("ACTIVE"));
    }

    // ---- password forgot / reset (public) -----------------------------------------------

    @Test
    void forgotIsPublicAndReturns202() throws Exception {
        when(authService.forgotPassword("anyone@example.com"))
                .thenReturn(new OtpSentResponse("If an account exists...", 60));

        mockMvc.perform(post("/api/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"anyone@example.com\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.resendAfterSeconds").value(60));
    }

    @Test
    void resendIsAlways202NeverRateLimitStatus() throws Exception {
        when(authService.resendVerificationCode(any())).thenReturn(new OtpSentResponse("If this email...", 60));

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/auth/verify-email/resend").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"rahim@example.com\"}"))
                    .andExpect(status().isAccepted());
        }
    }

    @Test
    void resetIsPublicAndReturns204() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"rahim@example.com\",\"code\":\"123456\",\"newPassword\":\"newShop2026\"}"))
                .andExpect(status().isNoContent());
        verify(authService).resetPassword(any());
    }

    @Test
    void resetEnforcesPasswordRules() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"rahim@example.com\",\"code\":\"123456\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItems("newPassword")))
                .andExpect(jsonPath("$.fieldErrors[*].message").value(hasItems(
                        "must be between 8 and 72 characters", "must contain at least one digit")));
        verifyNoInteractions(authService);
    }

    private String validToken() {
        when(userDetailsService.loadUserById("u1")).thenReturn(new AppUserDetails(
                "u1", "shopA", "rahim@example.com", "hash", Role.SHOP_OWNER, Role.SHOP_OWNER.defaultPermissions(), true, 0));
        return jwtService.generateAccessToken("u1", "shopA", Role.SHOP_OWNER, Role.SHOP_OWNER.defaultPermissions(), 0);
    }

    // ---- register / login / refresh / verify ---------------------------------------------

    @Test
    void registerIsPublicAndReturns201() throws Exception {
        when(authService.registerShop(any())).thenReturn(new RegisterShopResponse("ok", "rahim@example.com", 60));

        mockMvc.perform(post("/api/v1/auth/register-shop").contentType(MediaType.APPLICATION_JSON)
                        .content(validRegisterJson("rahim2026shop")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("rahim@example.com"))
                .andExpect(jsonPath("$.resendAfterSeconds").value(60));
    }

    @Test
    void weakPasswordGivesOneClearMessagePerBrokenRule() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register-shop").contentType(MediaType.APPLICATION_JSON)
                        .content(validRegisterJson("abcdefgh")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItems("password")))
                .andExpect(jsonPath("$.fieldErrors[*].message").value(hasItems("must contain at least one digit")));
        verifyNoInteractions(authService);
    }

    @Test
    void invalidFieldsAreAllReported() throws Exception {
        String json = """
                {"ownerName":"","email":"not-an-email","password":"1234","shopName":"R",
                 "shopPhone":"12345","facebookPageUrl":"facebook.com/x"}
                """;

        mockMvc.perform(post("/api/v1/auth/register-shop").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItems(
                        "ownerName", "email", "password", "shopName", "shopPhone", "facebookPageUrl")))
                .andExpect(jsonPath("$.fieldErrors[*].message").value(hasItems(
                        "must be between 8 and 72 characters",
                        "must contain at least one letter",
                        "must be a Bangladeshi mobile number like 01712345678")));
    }

    @Test
    void loginSetsHttpOnlyRefreshCookieAndReturnsAccessTokenInBody() throws Exception {
        when(authService.login(any())).thenReturn(new AuthService.AuthResult("ACCESS", "REFRESH", 900,
                new UserResponse("u1", "Rahim", "rahim@example.com", null, Role.SHOP_OWNER, List.of(), UserStatus.ACTIVE)));

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"rahim@example.com\",\"password\":\"rahim2026shop\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("ACCESS"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().string("Set-Cookie", allOf(
                        containsString("sd_refresh=REFRESH"),
                        containsString("HttpOnly"),
                        containsString("Path=/api/v1/auth"),
                        containsString("SameSite=Strict"),
                        containsString("Max-Age=2592000"))));
    }

    @Test
    void refreshReadsTheCookie() throws Exception {
        when(authService.refresh(eq("REFRESH"), eq("http://localhost:5173"))).thenReturn(
                new AuthService.AuthResult("NEW_ACCESS", "NEW_REFRESH", 900, null));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("sd_refresh", "REFRESH"))
                        .header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("NEW_ACCESS"))
                .andExpect(header().string("Set-Cookie", containsString("sd_refresh=NEW_REFRESH")));
    }

    @Test
    void verifyCodeMustBeSixDigits() throws Exception {
        mockMvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"rahim@example.com\",\"code\":\"12ab\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("code"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("must be 6 digits"));
    }

    private static String validRegisterJson(String password) {
        return """
                {"ownerName":"Rahim Uddin","email":"rahim@example.com","password":"%s",
                 "shopName":"Rahim Fashion","shopPhone":"01712345678"}
                """.formatted(password);
    }
}

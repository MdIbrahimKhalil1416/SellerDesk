package com.sellerdesk.common.security;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sellerdesk.common.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Whole security chain with the real Spring context: filter → URL rules → @PreAuthorize →
 * JSON error. Only the user lookup is mocked, so no database is needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PermissionSecurityTest.ProbeController.class)
class PermissionSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AppProperties appProperties;

    @MockitoBean
    private AppUserDetailsService userDetailsService;

    /** A tiny controller that only exists in this test. */
    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/probe/orders")
        @PreAuthorize("hasAuthority('ORDER_CREATE')")
        Map<String, String> createOrderProbe() {
            return Map.of("shopId", TenantContext.requireShopId());
        }
    }

    @Test
    void userWithPermissionGets200AndOwnShopId() throws Exception {
        String token = tokenFor("u1", "shopA", Role.MODERATOR, Role.MODERATOR.defaultPermissions(), 0);

        mockMvc.perform(get("/api/v1/probe/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shopId").value("shopA"));
    }

    @Test
    void userWithoutPermissionGets403InApiErrorFormat() throws Exception {
        String token = tokenFor("u2", "shopA", Role.PACKER, Role.PACKER.defaultPermissions(), 0);

        mockMvc.perform(get("/api/v1/probe/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.path").value("/api/v1/probe/orders"));
    }

    @Test
    void platformAdminGets403OnShopEndpoint() throws Exception {
        String token = tokenFor("admin", null, Role.PLATFORM_ADMIN, Set.of(), 0);

        mockMvc.perform(get("/api/v1/probe/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void missingTokenGets401Unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/probe/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void expiredTokenGets401TokenExpired() throws Exception {
        JwtService past = new JwtService(appProperties,
                Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC));
        String token = past.generateAccessToken("u1", "shopA", Role.SHOP_OWNER,
                EnumSet.allOf(Permission.class), 0);

        mockMvc.perform(get("/api/v1/probe/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void revokedTokenGets401InvalidToken() throws Exception {
        String token = tokenFor("u1", "shopA", Role.SHOP_OWNER, EnumSet.allOf(Permission.class), 0);
        // After the token was issued the user logged out: tokenVersion is now 1.
        givenUser("u1", "shopA", Role.SHOP_OWNER, EnumSet.allOf(Permission.class), 1);

        mockMvc.perform(get("/api/v1/probe/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void refreshTokenAsBearerGets401InvalidToken() throws Exception {
        String refresh = jwtService.generateRefreshToken("u1", 0);

        mockMvc.perform(get("/api/v1/probe/orders").header("Authorization", "Bearer " + refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    private String tokenFor(String userId, String shopId, Role role, Set<Permission> perms, int ver) {
        givenUser(userId, shopId, role, perms, ver);
        return jwtService.generateAccessToken(userId, shopId, role, perms, ver);
    }

    private void givenUser(String userId, String shopId, Role role, Set<Permission> perms, int ver) {
        when(userDetailsService.loadUserById(userId)).thenReturn(
                new AppUserDetails(userId, shopId, userId + "@example.com", "hash", role, perms, true, ver));
    }
}

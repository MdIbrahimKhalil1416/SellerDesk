package com.sellerdesk.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Checks the URL-level security rules and the serving of the React app with the full Spring context.
 * Needs no database: none of these requests touch MongoDB except /actuator/health,
 * and for that one we only check it is not blocked by security.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthIsPublic() throws Exception {
        int status = mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus();
        // 200 (UP) or 503 (DOWN, no database in this test). Never 401/403.
        assertThat(status).isIn(200, 503);
    }

    @Test
    void apiDocsArePublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    /** springdoc serves its own files; SpaWebConfig's "/**" handler must not hide them. */
    @Test
    void swaggerUiStillLoads() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("swagger")));
    }

    @Test
    void otherPathsRequireAuthenticationAndUseApiErrorFormat() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").value("/api/v1/orders"));
    }

    @Test
    void otherActuatorEndpointsAreNotPublic() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
    }

    /** Same origin everywhere: no CORS headers, so other sites' JavaScript cannot read responses. */
    @Test
    void noCorsHeadersForOtherOrigins() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mockMvc.perform(post("/api/v1/auth/login").header("Origin", "https://evil.example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    // ---- The React app (SpaWebConfig). Test files: src/test/resources/static/ ------------------

    @Test
    void rootForwardsToIndexHtmlWhichIsNeverCached() throws Exception {
        // Spring Boot's welcome page forwards "/" to index.html (MockMvc does not follow forwards).
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("SellerDesk test")))
                .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void spaRoutesForwardToIndexHtml() throws Exception {
        for (String route : new String[] {"/login", "/dashboard", "/orders/42"}) {
            mockMvc.perform(get(route))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                    .andExpect(content().string(containsString("SellerDesk test")))
                    .andExpect(header().string("Cache-Control", "no-cache"));
        }
    }

    @Test
    void hashedAssetsAreCachedForAYear() throws Exception {
        mockMvc.perform(get("/assets/index-test123.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("test asset")))
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));
    }

    @Test
    void missingFilesAreJson404NotIndexHtml() throws Exception {
        for (String path : new String[] {"/assets/old-abc.js", "/missing.png"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }

    @Test
    void unknownApiPathIsJson404ForLoggedInUser() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist").with(user("someone")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/api/v1/does-not-exist"));
    }

    @Test
    void unknownApiPathWithoutTokenIs401() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}

package com.sellerdesk.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Unit test of the filter alone: real JwtService, mocked user lookup, fake request/response.
 * The fake {@link FilterChain} records what the "rest of the application" would have seen.
 */
class JwtAuthenticationFilterTest {

    private final JwtService jwtService = JwtServiceTest.serviceAt(JwtServiceTest.NOW, JwtServiceTest.SECRET);
    private final AppUserDetailsService userDetailsService = mock(AppUserDetailsService.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, userDetailsService);

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    // What code running inside the request (controllers, services) saw:
    private final AtomicReference<Authentication> seenAuth = new AtomicReference<>();
    private final AtomicReference<String> seenShopId = new AtomicReference<>();
    private final FilterChain recordingChain = (req, res) -> {
        seenAuth.set(SecurityContextHolder.getContext().getAuthentication());
        seenShopId.set(TenantContext.getShopId());
    };

    private static final Set<Permission> MODERATOR_PERMS = Role.MODERATOR.defaultPermissions();

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void validTokenAuthenticatesUserAndSetsTenant() throws Exception {
        givenUser("user1", "shopA", true, 2);
        bearer(jwtService.generateAccessToken("user1", "shopA", Role.MODERATOR, MODERATOR_PERMS, 2));

        filter.doFilter(request, response, recordingChain);

        assertThat(seenAuth.get()).isNotNull();
        assertThat(seenAuth.get().isAuthenticated()).isTrue();
        assertThat(((AppUserDetails) seenAuth.get().getPrincipal()).userId()).isEqualTo("user1");
        assertThat(seenAuth.get().getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .contains("ORDER_CREATE", "ORDER_VIEW", "ROLE_MODERATOR")
                .doesNotContain("STAFF_MANAGE");
        assertThat(seenShopId.get()).isEqualTo("shopA");
        assertThat(request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE)).isNull();
    }

    @Test
    void tenantContextIsClearedAfterRequest() throws Exception {
        givenUser("user1", "shopA", true, 0);
        bearer(jwtService.generateAccessToken("user1", "shopA", Role.MODERATOR, MODERATOR_PERMS, 0));

        filter.doFilter(request, response, recordingChain);

        assertThat(seenShopId.get()).isEqualTo("shopA"); // was set during the request
        assertThat(TenantContext.getShopId()).isNull();  // and is gone afterwards
    }

    @Test
    void tenantContextIsClearedEvenWhenTheRequestThrows() {
        givenUser("user1", "shopA", true, 0);
        bearer(jwtService.generateAccessToken("user1", "shopA", Role.MODERATOR, MODERATOR_PERMS, 0));
        FilterChain failingChain = (req, res) -> {
            throw new IllegalStateException("boom in controller");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, failingChain))
                .hasMessage("boom in controller");
        assertThat(TenantContext.getShopId()).isNull();
    }

    @Test
    void noHeaderLeavesRequestAnonymousWithoutError() throws Exception {
        filter.doFilter(request, response, recordingChain);

        assertThat(seenAuth.get()).isNull();
        assertThat(seenShopId.get()).isNull();
        assertThat(request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE)).isNull();
        verifyNoInteractions(userDetailsService);
    }

    @Test
    void nonBearerHeaderIsIgnored() throws Exception {
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

        filter.doFilter(request, response, recordingChain);

        assertThat(seenAuth.get()).isNull();
        verifyNoInteractions(userDetailsService);
    }

    @Test
    void expiredTokenIsMarkedTokenExpired() throws Exception {
        JwtService earlier = JwtServiceTest.serviceAt(JwtServiceTest.NOW.minus(Duration.ofMinutes(20)),
                JwtServiceTest.SECRET);
        bearer(earlier.generateAccessToken("user1", "shopA", Role.MODERATOR, MODERATOR_PERMS, 0));

        filter.doFilter(request, response, recordingChain);

        assertRejected(JwtAuthenticationFilter.TOKEN_EXPIRED);
        verifyNoInteractions(userDetailsService);
    }

    @Test
    void malformedTokenIsMarkedInvalid() throws Exception {
        bearer("abc.def.ghi");

        filter.doFilter(request, response, recordingChain);

        assertRejected(JwtAuthenticationFilter.INVALID_TOKEN);
    }

    @Test
    void refreshTokenSentAsAccessTokenIsRejected() throws Exception {
        bearer(jwtService.generateRefreshToken("user1", 0));

        filter.doFilter(request, response, recordingChain);

        assertRejected(JwtAuthenticationFilter.INVALID_TOKEN);
    }

    @Test
    void oldTokenVersionIsRejected() throws Exception {
        // User logged out (tokenVersion 0 → 1) but still sends a token with ver 0.
        givenUser("user1", "shopA", true, 1);
        bearer(jwtService.generateAccessToken("user1", "shopA", Role.MODERATOR, MODERATOR_PERMS, 0));

        filter.doFilter(request, response, recordingChain);

        assertRejected(JwtAuthenticationFilter.INVALID_TOKEN);
    }

    @Test
    void disabledUserIsRejected() throws Exception {
        givenUser("user1", "shopA", false, 0);
        bearer(jwtService.generateAccessToken("user1", "shopA", Role.MODERATOR, MODERATOR_PERMS, 0));

        filter.doFilter(request, response, recordingChain);

        assertRejected(JwtAuthenticationFilter.INVALID_TOKEN);
    }

    @Test
    void deletedUserIsRejected() throws Exception {
        when(userDetailsService.loadUserById("ghost")).thenThrow(new UsernameNotFoundException("User not found"));
        bearer(jwtService.generateAccessToken("ghost", "shopA", Role.MODERATOR, MODERATOR_PERMS, 0));

        filter.doFilter(request, response, recordingChain);

        assertRejected(JwtAuthenticationFilter.INVALID_TOKEN);
    }

    @Test
    void tokenShopIdDifferentFromUsersShopIsRejected() throws Exception {
        givenUser("user1", "shopA", true, 0);
        bearer(jwtService.generateAccessToken("user1", "shopB", Role.MODERATOR, MODERATOR_PERMS, 0));

        filter.doFilter(request, response, recordingChain);

        assertRejected(JwtAuthenticationFilter.INVALID_TOKEN);
    }

    @Test
    void platformAdminIsAuthenticatedWithoutTenant() throws Exception {
        when(userDetailsService.loadUserById("admin")).thenReturn(new AppUserDetails(
                "admin", null, "admin@x.com", "hash", Role.PLATFORM_ADMIN, Set.of(), true, 0));
        bearer(jwtService.generateAccessToken("admin", null, Role.PLATFORM_ADMIN, Set.of(), 0));

        filter.doFilter(request, response, recordingChain);

        assertThat(seenAuth.get()).isNotNull();
        assertThat(seenAuth.get().getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_PLATFORM_ADMIN");
        assertThat(seenShopId.get()).isNull();
    }

    @Test
    void permissionsComeFromTheSignedToken() throws Exception {
        givenUser("user1", "shopA", true, 0);
        bearer(jwtService.generateAccessToken("user1", "shopA", Role.MODERATOR,
                EnumSet.of(Permission.ORDER_VIEW), 0));

        filter.doFilter(request, response, recordingChain);

        assertThat(seenAuth.get().getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ORDER_VIEW", "ROLE_MODERATOR");
    }

    // ---- helpers ------------------------------------------------------------------------

    private void givenUser(String userId, String shopId, boolean enabled, int tokenVersion) {
        when(userDetailsService.loadUserById(userId)).thenReturn(new AppUserDetails(
                userId, shopId, userId + "@example.com", "hash", Role.MODERATOR, MODERATOR_PERMS,
                enabled, tokenVersion));
    }

    private void bearer(String token) {
        request.addHeader("Authorization", "Bearer " + token);
    }

    /** The request still went through (public endpoints must work), but anonymously. */
    private void assertRejected(String expectedCode) {
        assertThat(seenAuth.get()).isNull();
        assertThat(seenShopId.get()).isNull();
        assertThat(request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE)).isEqualTo(expectedCode);
    }
}

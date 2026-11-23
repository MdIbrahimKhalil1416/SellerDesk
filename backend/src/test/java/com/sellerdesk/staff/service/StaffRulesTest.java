package com.sellerdesk.staff.service;

import static com.sellerdesk.common.security.Permission.ORDER_CREATE;
import static com.sellerdesk.common.security.Permission.ORDER_VIEW;
import static com.sellerdesk.common.security.Permission.REPORT_VIEW;
import static com.sellerdesk.common.security.Permission.STAFF_MANAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerdesk.auth.model.User;
import com.sellerdesk.common.error.AppException;
import com.sellerdesk.common.error.FieldValidationException;
import com.sellerdesk.common.error.RateLimitedException;
import com.sellerdesk.common.security.AppUserDetails;
import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import com.sellerdesk.staff.model.StaffInvitation;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class StaffRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    static AppUserDetails editor(String id, Role role, Set<Permission> permissions) {
        return new AppUserDetails(id, "shopA", id + "@example.com", "hash", role, permissions, true, 0);
    }

    static User user(String id, Role role) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setPermissions(EnumSet.copyOf(role.defaultPermissions()));
        return user;
    }

    private static String codeOf(ThrowingRunnable call) {
        try {
            call.run();
        } catch (AppException e) {
            return e.getCode();
        }
        throw new AssertionError("expected an AppException");
    }

    interface ThrowingRunnable {
        void run();
    }

    @Nested
    class Parsing {

        @Test
        void onlyStaffRolesAreAccepted() {
            assertThat(StaffRules.parseStaffRole("PACKER", "role")).isEqualTo(Role.PACKER);
            for (String bad : new String[] {"SHOP_OWNER", "PLATFORM_ADMIN", "packer", "BOSS", null}) {
                assertThatThrownBy(() -> StaffRules.parseStaffRole(bad, "role"))
                        .isInstanceOf(FieldValidationException.class);
            }
        }

        @Test
        void unknownPermissionIsAFieldError() {
            assertThat(StaffRules.parsePermissions(List.of("ORDER_VIEW", "ORDER_VIEW", "REPORT_VIEW")))
                    .containsExactlyInAnyOrder(ORDER_VIEW, REPORT_VIEW);
            assertThatThrownBy(() -> StaffRules.parsePermissions(List.of("ORDER_VIEW", "GOD_MODE")))
                    .isInstanceOf(FieldValidationException.class)
                    .satisfies(e -> assertThat(((FieldValidationException) e).getFieldErrors().getFirst().field())
                            .isEqualTo("permissions"));
            List<String> withNull = new ArrayList<>();
            withNull.add(null);
            assertThatThrownBy(() -> StaffRules.parsePermissions(withNull)).isInstanceOf(FieldValidationException.class);
        }

        @Test
        void emptyListMeansNoPermissions() {
            assertThat(StaffRules.parsePermissions(List.of())).isEmpty();
        }
    }

    @Nested
    class WhoMayChangeWhom {

        private final AppUserDetails owner = editor("owner", Role.SHOP_OWNER, EnumSet.allOf(Permission.class));
        private final AppUserDetails manager = editor("manager", Role.MODERATOR, EnumSet.of(STAFF_MANAGE, ORDER_VIEW));

        @Test
        void ownerRowIsReadOnlyForEveryone() {
            User shopOwner = user("owner", Role.SHOP_OWNER);
            assertThat(codeOf(() -> StaffRules.checkCanModify(manager, shopOwner))).isEqualTo("OWNER_READ_ONLY");
            // The owner editing themselves: the owner rule comes first.
            assertThat(codeOf(() -> StaffRules.checkCanModify(owner, shopOwner))).isEqualTo("OWNER_READ_ONLY");
        }

        @Test
        void nobodyChangesThemselves() {
            User self = user("manager", Role.MODERATOR);
            assertThatThrownBy(() -> StaffRules.checkCanModify(manager, self))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> {
                        assertThat(((AppException) e).getCode()).isEqualTo("CANNOT_MODIFY_SELF");
                        assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    });
        }

        @Test
        void otherStaffCanBeChanged() {
            assertThatCode(() -> StaffRules.checkCanModify(manager, user("packer", Role.PACKER))).doesNotThrowAnyException();
            assertThatCode(() -> StaffRules.checkCanModify(owner, user("packer", Role.PACKER))).doesNotThrowAnyException();
        }

        @Test
        void ownerMayGrantAnything() {
            assertThatCode(() -> StaffRules.checkGrantable(owner, Set.of(), EnumSet.allOf(Permission.class)))
                    .doesNotThrowAnyException();
        }

        @Test
        void nonOwnerMayOnlyGrantOwnPermissions() {
            assertThatCode(() -> StaffRules.checkGrantable(manager, Set.of(), Set.of(ORDER_VIEW))).doesNotThrowAnyException();
            assertThatThrownBy(() -> StaffRules.checkGrantable(manager, Set.of(), Set.of(ORDER_VIEW, REPORT_VIEW)))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("REPORT_VIEW")
                    .satisfies(e -> assertThat(((AppException) e).getCode()).isEqualTo("PERMISSION_NOT_GRANTABLE"));
        }

        @Test
        void keepingOrRemovingAPermissionTheEditorLacksIsAllowed() {
            Set<Permission> current = EnumSet.of(ORDER_VIEW, REPORT_VIEW, ORDER_CREATE);
            // keeps REPORT_VIEW (manager lacks it), removes ORDER_CREATE (manager lacks it too)
            assertThatCode(() -> StaffRules.checkGrantable(manager, current, EnumSet.of(ORDER_VIEW, REPORT_VIEW)))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    class ResendLimits {

        private StaffInvitation sentAt(Instant... sends) {
            StaffInvitation invitation = new StaffInvitation();
            invitation.setRecentSends(new ArrayList<>(List.of(sends)));
            invitation.setLastSentAt(sends[sends.length - 1]);
            return invitation;
        }

        @Test
        void within60SecondsIsTooSoonWithRetryAfter() {
            StaffInvitation invitation = sentAt(NOW.minusSeconds(45));
            assertThatThrownBy(() -> StaffRules.checkCanResend(invitation, NOW))
                    .isInstanceOf(RateLimitedException.class)
                    .satisfies(e -> {
                        RateLimitedException r = (RateLimitedException) e;
                        assertThat(r.getCode()).isEqualTo("INVITE_RESEND_TOO_SOON");
                        assertThat(r.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                        assertThat(r.getRetryAfterSeconds()).isEqualTo(15);
                    });
        }

        @Test
        void after60SecondsResendIsAllowed() {
            assertThatCode(() -> StaffRules.checkCanResend(sentAt(NOW.minusSeconds(60)), NOW)).doesNotThrowAnyException();
        }

        @Test
        void sixthSendWithin24HoursIsRejected() {
            StaffInvitation invitation = sentAt(NOW.minus(Duration.ofHours(20)), NOW.minus(Duration.ofHours(10)),
                    NOW.minus(Duration.ofHours(5)), NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofHours(1)));
            assertThatThrownBy(() -> StaffRules.checkCanResend(invitation, NOW))
                    .isInstanceOf(RateLimitedException.class)
                    .satisfies(e -> {
                        RateLimitedException r = (RateLimitedException) e;
                        assertThat(r.getCode()).isEqualTo("INVITE_RESEND_LIMIT");
                        // The oldest send leaves the 24 h window in 4 hours.
                        assertThat(r.getRetryAfterSeconds()).isEqualTo(Duration.ofHours(4).toSeconds());
                    });
        }

        @Test
        void sendsOlderThan24HoursDoNotCount() {
            StaffInvitation invitation = sentAt(NOW.minus(Duration.ofHours(30)), NOW.minus(Duration.ofHours(10)),
                    NOW.minus(Duration.ofHours(5)), NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofHours(1)));
            assertThatCode(() -> StaffRules.checkCanResend(invitation, NOW)).doesNotThrowAnyException();
        }

        @Test
        void recordSendDropsOldEntriesAndKeepsAtMostFive() {
            List<Instant> sends = new ArrayList<>();
            for (int h = 23; h >= 19; h--) {
                sends.add(NOW.minus(Duration.ofHours(h)));
            }
            sends.addFirst(NOW.minus(Duration.ofHours(48)));
            List<Instant> after = StaffRules.recordSend(sends, NOW);
            assertThat(after).hasSize(5).contains(NOW).doesNotContain(NOW.minus(Duration.ofHours(48)));
            assertThat(StaffRules.recordSend(null, NOW)).containsExactly(NOW);
        }
    }

    @Test
    void searchTextIsRegexEscaped() {
        String regex = StaffRules.containsRegex("a.b*(c)+[d]?$^|\\");
        assertThat(Pattern.compile(regex).matcher("x a.b*(c)+[d]?$^|\\ y").find()).isTrue();
        assertThat(Pattern.compile(regex).matcher("axb").find()).isFalse();
    }
}

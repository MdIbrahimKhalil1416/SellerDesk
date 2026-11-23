package com.sellerdesk.staff.service;

import com.sellerdesk.auth.model.User;
import com.sellerdesk.common.error.FieldValidationException;
import com.sellerdesk.common.security.AppUserDetails;
import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import com.sellerdesk.staff.model.StaffInvitation;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The staff business rules as small pure functions, so each rule has one place and one unit test
 * (docs/modules/STAFF.md §4, docs/SECURITY.md §3).
 */
public final class StaffRules {

    /** Roles that can be given through the staff API. Never SHOP_OWNER or PLATFORM_ADMIN. */
    public static final String STAFF_ROLE_REGEX = "MODERATOR|PACKER|ACCOUNTANT";
    /** {@code role} filter of the list: staff roles plus the owner. */
    public static final String LIST_ROLE_REGEX = "SHOP_OWNER|MODERATOR|PACKER|ACCOUNTANT";
    /** {@code status} filter of the list. INVITED = pending, not expired; EXPIRED = pending, expired. */
    public static final String LIST_STATUS_REGEX = "ACTIVE|DISABLED|INVITED|EXPIRED";

    public static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    public static final int MAX_SENDS_PER_DAY = 5;
    public static final Duration SEND_WINDOW = Duration.ofHours(24);

    private StaffRules() {
    }

    // ---- Parsing (→ 400 VALIDATION_FAILED with a field error) ----------------------------

    /** The DTO's {@code @Pattern} already allows only staff roles; this is the second line. */
    public static Role parseStaffRole(String value, String field) {
        if (value == null || !value.matches(STAFF_ROLE_REGEX)) {
            throw new FieldValidationException(field, "must be MODERATOR, PACKER or ACCOUNTANT");
        }
        return Role.valueOf(value);
    }

    /** Unknown or missing codes → field error on {@code permissions}. Duplicates are fine. */
    public static Set<Permission> parsePermissions(List<String> codes) {
        Set<Permission> result = EnumSet.noneOf(Permission.class);
        for (String code : codes) {
            try {
                result.add(Permission.valueOf(Objects.requireNonNull(code)));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new FieldValidationException("permissions", "unknown permission: " + code);
            }
        }
        return result;
    }

    // ---- Who may change whom (→ 403) -----------------------------------------------------

    /**
     * Owner rows are read-only for everybody (this also keeps the shop's one owner ACTIVE and an
     * owner forever), and nobody changes their own role, permissions or status. The owner rule is
     * checked first, so an owner editing their own row gets {@code OWNER_READ_ONLY}.
     */
    public static void checkCanModify(AppUserDetails editor, User target) {
        if (target.getRole() == Role.SHOP_OWNER) {
            throw StaffErrors.ownerReadOnly();
        }
        if (target.getId().equals(editor.userId())) {
            throw StaffErrors.cannotModifySelf();
        }
    }

    /**
     * A non-owner can only <b>grant</b> permissions they have themselves (no privilege escalation).
     * Granted = in {@code next} but not in {@code current}. Keeping or removing a permission the
     * editor lacks is allowed. For an invitation {@code current} is empty, so every permission
     * counts. The owner has all permissions and may grant anything.
     */
    public static void checkGrantable(AppUserDetails editor, Set<Permission> current, Set<Permission> next) {
        if (editor.role() == Role.SHOP_OWNER) {
            return;
        }
        Set<Permission> notGrantable = next.stream()
                .filter(p -> !current.contains(p))
                .filter(p -> !editor.permissions().contains(p))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Permission.class)));
        if (!notGrantable.isEmpty()) {
            throw StaffErrors.permissionNotGrantable(notGrantable.stream().map(Enum::name)
                    .collect(Collectors.joining(", ")));
        }
    }

    // ---- Resend limits (→ 429) -----------------------------------------------------------

    /**
     * 60 s between two sends of one invitation, and at most {@link #MAX_SENDS_PER_DAY} sends in any
     * 24 hours. Both answers say when the next send is allowed ({@code Retry-After}).
     */
    public static void checkCanResend(StaffInvitation invitation, Instant now) {
        Instant nextAllowed = invitation.getLastSentAt().plus(RESEND_COOLDOWN);
        if (now.isBefore(nextAllowed)) {
            throw StaffErrors.resendTooSoon(secondsUntil(now, nextAllowed));
        }
        List<Instant> recent = sendsInWindow(invitation.getRecentSends(), now);
        if (recent.size() >= MAX_SENDS_PER_DAY) {
            Instant oldest = recent.stream().min(Instant::compareTo).orElseThrow();
            throw StaffErrors.resendLimit(secondsUntil(now, oldest.plus(SEND_WINDOW)));
        }
    }

    /** {@code recentSends} after a send at {@code now}: old entries dropped, at most the limit kept. */
    public static List<Instant> recordSend(List<Instant> recentSends, Instant now) {
        List<Instant> result = new ArrayList<>(sendsInWindow(recentSends, now));
        result.add(now);
        result.sort(Instant::compareTo);
        return new ArrayList<>(result.subList(Math.max(0, result.size() - MAX_SENDS_PER_DAY), result.size()));
    }

    private static List<Instant> sendsInWindow(List<Instant> sends, Instant now) {
        Instant windowStart = now.minus(SEND_WINDOW);
        return sends == null ? List.of() : sends.stream().filter(t -> t.isAfter(windowStart)).toList();
    }

    private static long secondsUntil(Instant now, Instant then) {
        // Rounded up: "retry after 0 s" would invite an immediate second failure.
        return Math.max(1, (Duration.between(now, then).toMillis() + 999) / 1000);
    }

    // ---- Search --------------------------------------------------------------------------

    /** Case-insensitive "contains" regex for user input: every regex character is escaped. */
    public static String containsRegex(String text) {
        return text.replaceAll("[\\\\^$.|?*+()\\[\\]{}]", "\\\\$0");
    }
}

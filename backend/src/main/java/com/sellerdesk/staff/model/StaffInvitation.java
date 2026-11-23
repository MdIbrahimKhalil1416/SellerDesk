package com.sellerdesk.staff.model;

import com.sellerdesk.common.model.AuditableDocument;
import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * An invitation for someone to join a shop as staff (docs/modules/STAFF.md §2). Tenant-owned.
 *
 * <p>No {@code users} document exists until the invitation is accepted.
 *
 * <p>Indexes:
 * <ul>
 *   <li>{@code tokenHash} unique: the accept page finds the invitation by the hash of its token.</li>
 *   <li>{@code {shopId, email}} unique <b>only for PENDING</b> (a "partial" index): at most one open
 *       invitation per email per shop, while cancelled and accepted ones may pile up.</li>
 *   <li>{@code {shopId, status, createdAt}}: the staff list.</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = "tokenHash")
@Document("staff_invitations")
@CompoundIndexes({
        @CompoundIndex(name = "shopId_email_pending", def = "{'shopId': 1, 'email': 1}", unique = true,
                partialFilter = "{'status': 'PENDING'}"),
        @CompoundIndex(name = "shopId_status_createdAt", def = "{'shopId': 1, 'status': 1, 'createdAt': -1}")
})
public class StaffInvitation extends AuditableDocument {

    /** How long a sent link stays valid. */
    public static final Duration VALIDITY = Duration.ofDays(7);

    @Id
    private String id;

    @Field(targetType = FieldType.OBJECT_ID)
    private String shopId;

    /** Lowercase, trimmed. */
    private String email;

    /** MODERATOR, PACKER or ACCOUNTANT. Never SHOP_OWNER / PLATFORM_ADMIN. */
    private Role role;

    /** Copied to the user on accept. */
    private Set<Permission> permissions = EnumSet.noneOf(Permission.class);

    private InvitationStatus status;

    /**
     * SHA-256 (hex) of the token in the emailed link. The token itself is never stored or logged,
     * so a database leak reveals no usable links.
     */
    @Indexed(unique = true)
    private String tokenHash;

    /** Last send + {@link #VALIDITY}. */
    private Instant expiresAt;

    @Field(targetType = FieldType.OBJECT_ID)
    private String invitedBy;

    /** Emails sent for this invitation, first send = 1. */
    private int sendCount;

    private Instant lastSentAt;

    /**
     * Times of the most recent sends (at most {@code InvitationRules.MAX_SENDS_PER_DAY}), for the
     * "max 5 sends per 24 h" limit. {@code sendCount}/{@code lastSentAt} alone cannot express a
     * sliding 24-hour window.
     */
    private List<Instant> recentSends = new ArrayList<>();

    @Field(targetType = FieldType.OBJECT_ID)
    private String acceptedUserId;

    /** Optimistic locking: two resends / an accept and a cancel at the same time cannot both win. */
    @Version
    private Long version;

    /** PENDING but past {@code expiresAt}. */
    public boolean isExpired(Instant now) {
        return status == InvitationStatus.PENDING && !now.isBefore(expiresAt);
    }
}

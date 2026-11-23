package com.sellerdesk.auth.model;

import com.sellerdesk.common.model.AuditableDocument;
import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A person who can log in: shop owner, staff member, or platform admin (docs/SCHEMA.md §3).
 *
 * <p>Never returned from a controller (it contains {@code passwordHash}). Map it to a DTO.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = "passwordHash")
@Document("users")
@CompoundIndex(name = "shopId_role", def = "{'shopId': 1, 'role': 1}")
public class User extends AuditableDocument {

    @Id
    private String id;

    /** Lowercase, trimmed. Globally unique: one account per email. */
    @Indexed(unique = true)
    private String email;

    /** BCrypt hash. Set at registration or when a staff invitation is accepted. */
    private String passwordHash;

    private String name;

    private String phone;

    /** Stored as ObjectId (see docs/SCHEMA.md §1). {@code null} only for PLATFORM_ADMIN. */
    @Field(targetType = FieldType.OBJECT_ID)
    private String shopId;

    private Role role;

    /** Effective permissions. Starts as {@link Role#defaultPermissions()}. */
    private Set<Permission> permissions = EnumSet.noneOf(Permission.class);

    private UserStatus status;

    /** Incremented on logout, disable, role/permission change, password change: kills all tokens. */
    private int tokenVersion;

    @Field(targetType = FieldType.OBJECT_ID)
    private String invitedBy;

    private Instant lastLoginAt;

    /** Optimistic locking: concurrent edits → 409 instead of a silent overwrite. */
    @Version
    private Long version;
}

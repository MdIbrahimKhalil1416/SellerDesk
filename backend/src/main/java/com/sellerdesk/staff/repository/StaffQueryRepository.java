package com.sellerdesk.staff.repository;

import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import com.sellerdesk.common.security.TenantCriteria;
import com.sellerdesk.staff.dto.StaffRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

/**
 * The combined staff list: members ({@code users}) and pending invitations
 * ({@code staff_invitations}) in one page (docs/modules/STAFF.md §3.1).
 *
 * <p>One aggregation on {@code users} that appends the invitations with {@code $unionWith}.
 * <b>Each side starts with its own {@code shopId} match</b> ({@link TenantCriteria}), before any
 * other stage, so the other shop's documents never enter the pipeline.
 *
 * <p>Read-only access to {@code users}: writes stay with the {@code auth} module (UserService).
 *
 * <p>Order: owner first (group 0), then invitations newest first (group 1), then members by name
 * (group 2). The pipeline is written as plain BSON documents, which map one-to-one to the MongoDB
 * documentation and are easier to read here than Spring's aggregation builder.
 */
@Repository
@RequiredArgsConstructor
public class StaffQueryRepository {

    private static final String USERS = "users";
    private static final String INVITATIONS = "staff_invitations";

    private final MongoTemplate mongoTemplate;

    /**
     * @param containsRegex already regex-escaped, or {@code null}
     * @param role          {@code null} = all roles
     * @param status        ACTIVE / DISABLED / INVITED / EXPIRED, {@code null} = all
     * @param now           what "expired" is measured against
     */
    public record Filter(String containsRegex, Role role, String status, Instant now, int page, int size) {
    }

    public record Result(List<StaffRow> content, long totalElements) {
    }

    public Result list(String shopId, Filter filter) {
        boolean wantMembers = filter.status() == null || "ACTIVE".equals(filter.status()) || "DISABLED".equals(filter.status());
        boolean wantInvitations = filter.status() == null || "INVITED".equals(filter.status()) || "EXPIRED".equals(filter.status());
        Date now = Date.from(filter.now());

        // ---- members side ----
        Document memberMatch = new Document(TenantCriteria.of(shopId).getCriteriaObject());
        // Members are ACTIVE or DISABLED. $in [] matches nothing when only invitations are wanted.
        memberMatch.append("status", new Document("$in",
                !wantMembers ? List.of() : filter.status() == null ? List.of("ACTIVE", "DISABLED") : List.of(filter.status())));
        if (filter.role() != null) {
            memberMatch.append("role", filter.role().name());
        }
        if (filter.containsRegex() != null) {
            memberMatch.append("$or", List.of(
                    new Document("name", regex(filter.containsRegex())),
                    new Document("email", regex(filter.containsRegex()))));
        }

        List<Bson> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match", memberMatch));
        pipeline.add(new Document("$project", new Document()
                .append("_id", 0)
                .append("kind", StaffRow.MEMBER)
                .append("id", new Document("$toString", "$_id"))
                .append("name", 1).append("email", 1).append("role", 1).append("permissions", 1)
                .append("status", 1).append("lastLoginAt", 1)
                .append("g", new Document("$cond", List.of(new Document("$eq", List.of("$role", "SHOP_OWNER")), 0, 2)))
                .append("sortName", new Document("$toLower", new Document("$ifNull", List.of("$name", ""))))));

        // ---- invitations side ----
        if (wantInvitations) {
            Document invitationMatch = new Document(TenantCriteria.of(shopId).getCriteriaObject())
                    .append("status", "PENDING");
            if ("INVITED".equals(filter.status())) {
                invitationMatch.append("expiresAt", new Document("$gt", now));
            } else if ("EXPIRED".equals(filter.status())) {
                invitationMatch.append("expiresAt", new Document("$lte", now));
            }
            if (filter.role() != null) {
                invitationMatch.append("role", filter.role().name());
            }
            if (filter.containsRegex() != null) {
                invitationMatch.append("email", regex(filter.containsRegex())); // invitations have no name
            }
            List<Document> invitationPipeline = List.of(
                    new Document("$match", invitationMatch),
                    new Document("$project", new Document()
                            .append("_id", 0)
                            .append("kind", StaffRow.INVITATION)
                            .append("id", new Document("$toString", "$_id"))
                            .append("email", 1).append("role", 1).append("permissions", 1)
                            .append("status", new Document("$cond",
                                    List.of(new Document("$lte", List.of("$expiresAt", now)), "EXPIRED", "INVITED")))
                            .append("invitedAt", "$createdAt")
                            .append("expiresAt", 1).append("lastSentAt", 1)
                            // $literal: a plain 1 in $project would mean "include field g".
                            .append("g", new Document("$literal", 1))
                            .append("sortTime", "$createdAt")));
            pipeline.add(new Document("$unionWith", new Document("coll", INVITATIONS).append("pipeline", invitationPipeline)));
        }

        // ---- order + page ----
        Document sort = new Document("g", 1).append("sortTime", -1).append("sortName", 1).append("id", 1);
        pipeline.add(new Document("$facet", new Document()
                .append("rows", List.of(
                        new Document("$sort", sort),
                        new Document("$skip", (long) filter.page() * filter.size()),
                        new Document("$limit", filter.size())))
                .append("total", List.of(new Document("$count", "n")))));

        Document result = mongoTemplate.getCollection(USERS).aggregate(pipeline).first();
        if (result == null) {
            return new Result(List.of(), 0);
        }
        List<Document> totals = result.getList("total", Document.class);
        long total = totals.isEmpty() ? 0 : ((Number) totals.getFirst().get("n")).longValue();
        List<StaffRow> rows = result.getList("rows", Document.class).stream().map(StaffQueryRepository::toRow).toList();
        return new Result(rows, total);
    }

    private static Document regex(String pattern) {
        return new Document("$regex", pattern).append("$options", "i");
    }

    private static StaffRow toRow(Document d) {
        List<String> codes = d.getList("permissions", String.class, List.of());
        List<Permission> permissions = codes.stream().map(Permission::valueOf).sorted().toList();
        return new StaffRow(
                d.getString("kind"),
                d.getString("id"),
                d.getString("name"),
                d.getString("email"),
                Role.valueOf(d.getString("role")),
                permissions,
                d.getString("status"),
                instant(d, "lastLoginAt"),
                instant(d, "invitedAt"),
                instant(d, "expiresAt"),
                instant(d, "lastSentAt"));
    }

    private static Instant instant(Document d, String key) {
        Date date = d.getDate(key);
        return date == null ? null : date.toInstant();
    }
}

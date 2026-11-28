package com.sellerdesk.customer.repository;

import com.sellerdesk.common.security.TenantCriteria;
import com.sellerdesk.customer.model.Customer;
import com.sellerdesk.customer.model.CustomerAddress;
import com.sellerdesk.customer.model.CustomerStatus;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * Hand-written customer queries: search, targeted updates, tags. Every query starts with
 * {@link TenantCriteria#of} (docs/SECURITY.md §4, layer 4).
 *
 * <h2>Two kinds of update</h2>
 * <ul>
 *   <li>{@link #modifyVersioned}: profile and status changes. The caller puts
 *       {@code version == loaded version} in the condition and {@code $inc version} in the update.</li>
 *   <li>{@link #modifyUnversioned}: address changes and order counters. These must <b>not</b>
 *       change {@code version}, or an open "edit customer" form would fail after every order.
 *       {@code MongoTemplate.findAndModify(..., Customer.class)} would increment the {@code @Version}
 *       field on its own, so this one runs against the raw collection.</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class CustomerQueryRepository {

    private static final String COLLECTION = "customers";

    private final MongoTemplate mongoTemplate;

    /** Search filters for {@link #search}. {@code status == null} means all statuses. */
    public record Search(String phonePrefixRegex, String phoneSuffixReversedRegex, String namePrefixRegex,
                         String tagKey, CustomerStatus status, Sort sort, int page, int size) {
    }

    public record SearchResult(List<Customer> content, long totalElements) {
    }

    public record TagCount(String name, long customerCount) {
    }

    /** Real ObjectIds, so the same criteria also work against the raw collection (no type mapping). */
    private static Criteria byId(String shopId, String customerId) {
        return TenantCriteria.of(shopId).and("_id").is(new ObjectId(customerId));
    }

    private static Query query(String shopId, String customerId, Criteria condition) {
        Criteria criteria = byId(shopId, customerId);
        return new Query(condition == null ? criteria : new Criteria().andOperator(criteria, condition));
    }

    /** Atomic update of one customer of the shop if it matches {@code condition}; the new document, or empty. */
    public Optional<Customer> modifyVersioned(String shopId, String customerId, Criteria condition, Update update) {
        return Optional.ofNullable(mongoTemplate.findAndModify(query(shopId, customerId, condition), update,
                FindAndModifyOptions.options().returnNew(true), Customer.class));
    }

    /** Like {@link #modifyVersioned}, but never touches {@code version} (see class comment). */
    public Optional<Customer> modifyUnversioned(String shopId, String customerId, Criteria condition, Update update) {
        Query q = query(shopId, customerId, condition);
        Document updated = mongoTemplate.findAndModify(q, update, FindAndModifyOptions.options().returnNew(true),
                Document.class, COLLECTION);
        return Optional.ofNullable(updated).map(d -> mongoTemplate.getConverter().read(Customer.class, d));
    }

    /**
     * Addresses as stored documents, for a {@code $set} on the raw collection: written with the
     * mapping converter, so {@code addressId} becomes an ObjectId and nulls are left out, exactly as
     * on create.
     */
    public List<Document> addressDocuments(List<CustomerAddress> addresses) {
        return addresses.stream().map(a -> {
            Document d = new Document();
            mongoTemplate.getConverter().write(a, d);
            d.remove("_class");
            return d;
        }).toList();
    }

    /** True if another customer of the shop already uses this phone. */
    public boolean phoneTaken(String shopId, String phone, String excludeCustomerId) {
        Criteria criteria = TenantCriteria.of(shopId).and("phone").is(phone);
        if (excludeCustomerId != null) {
            criteria = criteria.and("_id").ne(new ObjectId(excludeCustomerId));
        }
        return mongoTemplate.exists(new Query(criteria), Customer.class);
    }

    /**
     * The customer list. A phone search matches {@code phoneKeys} by prefix or
     * {@code phoneKeysReversed} by prefix ("ends with"), both through their multikey index; a text
     * search matches {@code nameLower} by prefix.
     */
    public SearchResult search(String shopId, Search s) {
        Criteria criteria = TenantCriteria.of(shopId);
        if (s.status() != null) {
            criteria = criteria.and("status").is(s.status());
        }
        if (s.tagKey() != null) {
            criteria = criteria.and("tagKeys").is(s.tagKey());
        }
        if (s.phonePrefixRegex() != null) {
            criteria = s.phoneSuffixReversedRegex() == null
                    ? criteria.and("phoneKeys").regex(s.phonePrefixRegex())
                    : criteria.orOperator(
                            Criteria.where("phoneKeys").regex(s.phonePrefixRegex()),
                            Criteria.where("phoneKeysReversed").regex(s.phoneSuffixReversedRegex()));
        } else if (s.namePrefixRegex() != null) {
            criteria = criteria.and("nameLower").regex(s.namePrefixRegex());
        }
        Query query = new Query(criteria);
        long total = mongoTemplate.count(query, Customer.class);
        query.with(s.sort()).skip((long) s.page() * s.size()).limit(s.size());
        return new SearchResult(mongoTemplate.find(query, Customer.class), total);
    }

    /** Tags of active customers with their count, sorted by name (case-insensitive). */
    public List<TagCount> tags(String shopId) {
        List<AggregationOperation> ops = List.of(
                Aggregation.match(TenantCriteria.of(shopId).and("status").is(CustomerStatus.ACTIVE.name())),
                raw(new Document("$unwind", "$tags")),
                raw(new Document("$group", new Document("_id", new Document("$toLower", "$tags"))
                        .append("name", new Document("$first", "$tags"))
                        .append("customerCount", new Document("$sum", 1)))),
                raw(new Document("$sort", new Document("_id", 1))));
        return mongoTemplate.aggregate(Aggregation.newAggregation(ops), COLLECTION, Document.class)
                .getMappedResults().stream()
                .map(d -> new TagCount(d.getString("name"), ((Number) d.get("customerCount")).longValue()))
                .toList();
    }

    private static AggregationOperation raw(Document stage) {
        return context -> stage;
    }
}

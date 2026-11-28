package com.sellerdesk.product.repository;

import com.sellerdesk.common.security.TenantCriteria;
import com.sellerdesk.product.model.Product;
import com.sellerdesk.product.model.ProductStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * Hand-written MongoDB queries for products: search, targeted updates, lookup and categories.
 * Every query starts with {@link TenantCriteria#of} (docs/SECURITY.md §4, layer 4).
 *
 * <h2>Why targeted updates</h2>
 * Inventory changes {@code variants.$.stock.*} with {@code $inc} at any moment. If we loaded the
 * product, changed the name and saved the whole document, we would write back the stock numbers
 * we read a moment ago and silently undo a reservation (a <i>lost update</i>). A {@code $set} of
 * just the changed fields never touches {@code stock}. The {@code version} condition still
 * catches two people editing the same product at the same time.
 */
@Repository
@RequiredArgsConstructor
public class ProductQueryRepository {

    private static final String COLLECTION = "products";

    private final MongoTemplate mongoTemplate;

    /** Search filters for {@link #search}. {@code status == null} means all statuses. */
    public record Search(String skuExact, String namePrefixRegex, String categoryKey, ProductStatus status,
                         ProductSort sort, int page, int size) {
    }

    public record SearchResult(List<Product> content, long totalElements) {
    }

    /** {@code {_id, shopId}}, the start of every single-product update. */
    private static Criteria byId(String shopId, String productId) {
        return TenantCriteria.of(shopId).and("_id").is(productId);
    }

    /**
     * Applies {@code update} to the product if it belongs to the shop and matches {@code condition}
     * (e.g. {@code version == 3}); returns the <b>new</b> document, or empty if nothing matched.
     * Find-and-modify is one atomic step on the server, so no other write can slip in between.
     */
    public Optional<Product> modify(String shopId, String productId, Criteria condition, Update update) {
        Criteria criteria = byId(shopId, productId);
        Query query = new Query(condition == null ? criteria : new Criteria().andOperator(criteria, condition));
        return Optional.ofNullable(mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), Product.class));
    }

    /**
     * Which of these SKUs are used by <b>other</b> products of the shop. One query for the
     * friendly error message; the unique index is the real guarantee.
     */
    public Set<String> findSkusInUse(String shopId, Collection<String> skus, String excludeProductId) {
        Criteria criteria = TenantCriteria.of(shopId).and("variants.sku").in(skus);
        if (excludeProductId != null) {
            criteria = criteria.and("_id").ne(excludeProductId);
        }
        Query query = new Query(criteria);
        query.fields().include("variants.sku");
        return mongoTemplate.find(query, Product.class).stream()
                .flatMap(p -> p.getVariants().stream())
                .map(v -> v.getSku())
                .filter(skus::contains)
                .collect(Collectors.toSet());
    }

    /**
     * The product list. {@code q} matches an exact SKU (index on {@code variants.sku}) or the
     * start of the name (index on {@code nameLower}) in one {@code $or}.
     */
    public SearchResult search(String shopId, Search search) {
        Criteria criteria = TenantCriteria.of(shopId);
        if (search.status() != null) {
            criteria = criteria.and("status").is(search.status());
        }
        if (search.categoryKey() != null) {
            criteria = criteria.and("categoryKey").is(search.categoryKey());
        }
        if (search.skuExact() != null) {
            criteria = criteria.orOperator(
                    Criteria.where("variants.sku").is(search.skuExact()),
                    Criteria.where("nameLower").regex(search.namePrefixRegex()));
        }
        Query query = new Query(criteria);
        long total = mongoTemplate.count(query, Product.class);
        query.with(search.sort().sort())
                .skip((long) search.page() * search.size())
                .limit(search.size());
        return new SearchResult(mongoTemplate.find(query, Product.class), total);
    }

    /**
     * The products of this shop that hold any of the given variants (for orders). Uses the
     * {@code shopId_variantId} index. Ids must be valid ObjectIds (checked by the caller).
     */
    public List<Product> findByVariantIds(String shopId, Collection<String> variantIds) {
        List<org.bson.types.ObjectId> ids = variantIds.stream().map(org.bson.types.ObjectId::new).toList();
        Query query = new Query(TenantCriteria.of(shopId).and("variants.variantId").in(ids));
        query.fields().include("name", "status", "variants.variantId", "variants.sku", "variants.name",
                "variants.price", "variants.active");
        return mongoTemplate.find(query, Product.class);
    }

    /**
     * Active products for the order-form lookup, with only the fields it needs. Inactive variants
     * and products without active variants are filtered out by the service.
     */
    public List<Product> findActiveForLookup(String shopId) {
        Query query = new Query(TenantCriteria.of(shopId).and("status").is(ProductStatus.ACTIVE))
                .with(Sort.by(Sort.Direction.ASC, "nameLower"));
        query.fields()
                .include("name", "variants.variantId", "variants.sku", "variants.name", "variants.price",
                        "variants.active")
                .slice("images", 1);
        return mongoTemplate.find(query, Product.class);
    }

    /** Categories in use with their product count, sorted by name. */
    public List<CategoryCount> categories(String shopId) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(TenantCriteria.of(shopId).and("categoryKey").ne(null)),
                Aggregation.group("categoryKey").first("category").as("name").count().as("productCount"),
                Aggregation.sort(Sort.Direction.ASC, "_id"));
        return mongoTemplate.aggregate(aggregation, COLLECTION, Document.class).getMappedResults().stream()
                .map(d -> new CategoryCount(d.getString("name"), ((Number) d.get("productCount")).longValue()))
                .toList();
    }

    public record CategoryCount(String name, long productCount) {
    }

    /**
     * Deletes the product only if no variant has stock on hand or reserved. Checked in the same
     * atomic step as the delete, so stock arriving a moment earlier cannot be lost.
     *
     * @return 1 if deleted, 0 if not found or stocked
     */
    public long deleteIfUnstocked(String shopId, String productId) {
        Criteria hasStock = new Criteria().orOperator(
                Criteria.where("stock.onHand").gt(0),
                Criteria.where("stock.reserved").gt(0));
        Criteria criteria = byId(shopId, productId).and("variants").not().elemMatch(hasStock);
        return mongoTemplate.remove(new Query(criteria), Product.class).getDeletedCount();
    }
}

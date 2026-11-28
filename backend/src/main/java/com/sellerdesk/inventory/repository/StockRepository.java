package com.sellerdesk.inventory.repository;

import com.sellerdesk.common.security.TenantCriteria;
import com.sellerdesk.product.model.Product;
import com.sellerdesk.product.model.ProductStatus;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * Stock numbers inside {@code products.variants[].stock} (docs/modules/INVENTORY.md §2, §4).
 *
 * <p>The Inventory module owns these fields (docs/ARCHITECTURE.md §1): this class writes
 * <b>only</b> {@code variants.$.stock.*}, always with {@code $inc} on the fields that change
 * together, never with a {@code $set} of a value read earlier. It never touches the product's
 * {@code version} or {@code updatedAt} (see {@link #applyChange}), so an open product edit form is never invalidated by a
 * stock change. Every query starts with {@link TenantCriteria#of}.
 */
@Repository
@RequiredArgsConstructor
public class StockRepository {

    private static final String COLLECTION = "products";
    private static final String VARIANT_ID = "variants.variantId";

    private final MongoTemplate mongoTemplate;

    /** The product that holds the variant, if both belong to the shop. */
    public Optional<Product> findByVariant(String shopId, String variantId) {
        Query query = new Query(TenantCriteria.of(shopId).and(VARIANT_ID).is(new ObjectId(variantId)));
        return Optional.ofNullable(mongoTemplate.findOne(query, Product.class));
    }

    /** The products of the shop holding any of the variants, with only variant ids and stock. */
    public List<Product> findStockOfVariants(String shopId, Collection<String> variantIds) {
        List<ObjectId> ids = variantIds.stream().map(ObjectId::new).toList();
        Query query = new Query(TenantCriteria.of(shopId).and(VARIANT_ID).in(ids));
        query.fields().include("variants.variantId", "variants.stock");
        return mongoTemplate.find(query, Product.class);
    }

    /**
     * Atomically applies a stock change <b>if</b> the variant matches {@code condition}
     * (e.g. {@code stock.available >= 3}); returns the updated product, or empty if nothing matched
     * (variant not in this shop, or the condition failed).
     *
     * <p>Check and change are one {@code findAndModify} on the server, so two requests for the last
     * unit cannot both succeed: MongoDB applies them one after the other, and the second no longer
     * matches. {@code $elemMatch} makes the condition apply to <b>the same</b> array element as the
     * variant id, and {@code variants.$} updates exactly that element.
     *
     * @param condition criteria on the variant's own fields, e.g. {@code where("stock.available").gte(3)}
     */
    public Optional<Product> applyChange(String shopId, String variantId, Criteria condition,
                                         int onHandDelta, int reservedDelta) {
        Criteria variant = Criteria.where("variantId").is(new ObjectId(variantId));
        Criteria element = condition == null ? variant : new Criteria().andOperator(variant, condition);
        Query query = new Query(TenantCriteria.of(shopId).and("variants").elemMatch(element));
        Update update = new Update()
                .inc("variants.$.stock.onHand", onHandDelta)
                .inc("variants.$.stock.reserved", reservedDelta)
                .inc("variants.$.stock.available", onHandDelta - reservedDelta);
        // Document.class + collection name, not Product.class: with the entity class, MongoTemplate
        // would also increment the product's @Version field, and every stock change would make an
        // open product edit form fail with 409. The criteria already hold real ObjectIds.
        Document updated = mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), Document.class, COLLECTION);
        return Optional.ofNullable(updated).map(d -> mongoTemplate.getConverter().read(Product.class, d));
    }

    // ==== Stock list ======================================================================

    /** Stock status filter of the list. */
    public enum StatusFilter { ALL, LOW, OUT, LOW_OR_OUT }

    /** List sort orders. */
    public enum ListSort { NAME, AVAILABLE }

    /**
     * @param skuExact          uppercased {@code q} (exact SKU), or {@code null}
     * @param namePrefixRegex   {@code ^<escaped lowercase q>} for {@code nameLower}, or {@code null}
     * @param shopDefault       the shop's {@code defaultLowStockThreshold}, used where a variant has none
     * @param includeInactive   {@code false}: only active variants of ACTIVE products
     */
    public record ListQuery(String skuExact, String namePrefixRegex, StatusFilter status, boolean includeInactive,
                            ListSort sort, int shopDefault, int page, int size) {
    }

    /** One row per variant, raw from the aggregation. */
    public record Row(String productId, String productName, ProductStatus productStatus, String coverImageUrl,
                      String variantId, String sku, String variantName, boolean active,
                      int onHand, int reserved, int available, Integer ownThreshold) {
    }

    public record ListResult(List<Row> rows, long totalElements) {
    }

    /**
     * The stock list as one aggregation on one shop's products:
     * {@code $match} (shop, status, q; uses the product indexes) → {@code $unwind} variants →
     * {@code $match} active / SKU → threshold ({@code $ifNull} with the shop default) →
     * status filter ({@code $expr}, comparing two fields, so it cannot use an index; the first
     * {@code $match} already limits the scan to one shop) → sort → one page + total count.
     */
    public ListResult list(String shopId, ListQuery q) {
        List<AggregationOperation> ops = new ArrayList<>();

        Criteria productMatch = TenantCriteria.of(shopId);
        if (!q.includeInactive()) {
            productMatch = productMatch.and("status").is(ProductStatus.ACTIVE.name());
        }
        Criteria text = textCriteria(q);
        if (text != null) {
            productMatch = new Criteria().andOperator(productMatch, text);
        }
        ops.add(Aggregation.match(productMatch));
        ops.add(stage("$unwind", new Document("path", "$variants")));

        List<Criteria> variantMatch = new ArrayList<>();
        if (!q.includeInactive()) {
            variantMatch.add(Criteria.where("variants.active").is(true));
        }
        if (text != null) {
            // After $unwind: a SKU search keeps only that variant, a name search keeps all of them.
            variantMatch.add(text);
        }
        if (!variantMatch.isEmpty()) {
            ops.add(Aggregation.match(new Criteria().andOperator(variantMatch)));
        }

        ops.add(stage("$addFields", new Document("threshold",
                new Document("$ifNull", List.of("$variants.lowStockThreshold", q.shopDefault())))));

        switch (q.status()) {
            case OUT -> ops.add(stage("$match", new Document("variants.stock.available", new Document("$lte", 0))));
            case LOW -> ops.add(stage("$match", new Document("variants.stock.available", new Document("$gt", 0))
                    .append("$expr", new Document("$lte", List.of("$variants.stock.available", "$threshold")))));
            // available <= 0 is always <= threshold (threshold >= 0), so one comparison covers both.
            case LOW_OR_OUT -> ops.add(stage("$match",
                    new Document("$expr", new Document("$lte", List.of("$variants.stock.available", "$threshold")))));
            case ALL -> {
                // no filter
            }
        }

        Document sort = new Document();
        if (q.sort() == ListSort.AVAILABLE) {
            sort.append("variants.stock.available", 1);
        }
        sort.append("nameLower", 1).append("variants.sku", 1);
        ops.add(stage("$sort", sort));

        Document project = new Document("_id", 0)
                .append("productId", "$_id")
                .append("productName", "$name")
                .append("productStatus", "$status")
                .append("coverImageUrl", new Document("$arrayElemAt", List.of("$images.url", 0)))
                .append("variant", "$variants");
        List<Document> rowsPipeline = List.of(
                new Document("$skip", (long) q.page() * q.size()),
                new Document("$limit", q.size()),
                new Document("$project", project));
        ops.add(stage("$facet", new Document("rows", rowsPipeline)
                .append("total", List.of(new Document("$count", "n")))));

        Document result = mongoTemplate.aggregate(Aggregation.newAggregation(ops), COLLECTION, Document.class)
                .getUniqueMappedResult();
        if (result == null) {
            return new ListResult(List.of(), 0);
        }
        List<Document> total = result.getList("total", Document.class);
        long totalElements = total.isEmpty() ? 0 : ((Number) total.get(0).get("n")).longValue();
        List<Row> rows = result.getList("rows", Document.class).stream().map(StockRepository::toRow).toList();
        return new ListResult(rows, totalElements);
    }

    private static Criteria textCriteria(ListQuery q) {
        if (q.skuExact() == null) {
            return null;
        }
        return new Criteria().orOperator(
                Criteria.where("variants.sku").is(q.skuExact()),
                Criteria.where("nameLower").regex(q.namePrefixRegex()));
    }

    /** A raw pipeline stage. Used where Spring's builder has no equivalent ($facet with $count, $expr). */
    private static AggregationOperation stage(String operator, Document body) {
        Document stage = new Document(operator, body);
        return context -> stage;
    }

    private static Row toRow(Document d) {
        Document v = d.get("variant", Document.class);
        Document stock = v.get("stock", Document.class);
        String status = d.getString("productStatus");
        Number own = (Number) v.get("lowStockThreshold");
        return new Row(
                d.getObjectId("productId").toHexString(),
                d.getString("productName"),
                status == null ? null : ProductStatus.valueOf(status),
                d.getString("coverImageUrl"),
                v.getObjectId("variantId").toHexString(),
                v.getString("sku"),
                v.getString("name"),
                Boolean.TRUE.equals(v.getBoolean("active")),
                intOf(stock, "onHand"),
                intOf(stock, "reserved"),
                intOf(stock, "available"),
                own == null ? null : own.intValue());
    }

    private static int intOf(Document stock, String field) {
        Object value = stock == null ? null : stock.get(field);
        return value instanceof Number n ? n.intValue() : 0;
    }
}

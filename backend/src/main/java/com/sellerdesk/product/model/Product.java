package com.sellerdesk.product.model;

import com.sellerdesk.common.model.AuditableDocument;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A product of a shop with its variants and images (docs/modules/PRODUCT.md §2).
 *
 * <p><b>Saved as a whole only once, on create.</b> Every later change is a targeted
 * {@code $set}/{@code $push} in {@code ProductQueryRepository}, because Inventory changes
 * {@code variants.$.stock} at any moment and a full save would write old stock numbers back.
 *
 * <p>Indexes all start with {@code shopId} (docs/SCHEMA.md §10). The first one is unique: a SKU
 * exists once per shop. It is a <i>multikey</i> index (one entry per variant), and MongoDB only
 * compares <i>different</i> documents, so duplicates inside one product are checked by the service.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
@Document("products")
@CompoundIndexes({
        @CompoundIndex(name = "shopId_sku", def = "{'shopId': 1, 'variants.sku': 1}", unique = true),
        @CompoundIndex(name = "shopId_variantId", def = "{'shopId': 1, 'variants.variantId': 1}"),
        @CompoundIndex(name = "shopId_status_name", def = "{'shopId': 1, 'status': 1, 'nameLower': 1}"),
        @CompoundIndex(name = "shopId_status_category_name", def = "{'shopId': 1, 'status': 1, 'categoryKey': 1, 'nameLower': 1}"),
        @CompoundIndex(name = "shopId_status_updatedAt", def = "{'shopId': 1, 'status': 1, 'updatedAt': -1}")
})
public class Product extends AuditableDocument {

    @Id
    private String id;

    @Field(targetType = FieldType.OBJECT_ID)
    private String shopId;

    private String name;

    /** Lowercase copy of {@link #name} for prefix search and sorting. */
    private String nameLower;

    private String description;

    /** Display form: trimmed, single spaces. */
    private String category;

    /** Lowercase {@link #category}; used for filter and distinct. */
    private String categoryKey;

    /** 0–8, first = cover. */
    private List<ProductImage> images = new ArrayList<>();

    private ProductStatus status;

    /** Poisha, over <b>active</b> variants; {@code null} if none is active. */
    private Long minPrice;

    private Long maxPrice;

    /** 1–100. */
    private List<Variant> variants = new ArrayList<>();

    /** Optimistic locking. Targeted updates filter on it and increment it themselves. */
    @Version
    private Long version;

    @Field(targetType = FieldType.OBJECT_ID)
    private String createdBy;
}

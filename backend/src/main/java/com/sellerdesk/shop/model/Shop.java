package com.sellerdesk.shop.model;

import com.sellerdesk.common.model.Address;
import com.sellerdesk.common.model.AuditableDocument;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A seller's shop: the tenant (docs/SCHEMA.md §2). Its {@code _id} <b>is</b> the {@code shopId}
 * stored in every other tenant document.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
@Document("shops")
public class Shop extends AuditableDocument {

    @Id
    private String id;

    private String name;

    /** One owner per shop, and one shop per owner (unique). */
    @Indexed(unique = true)
    @Field(targetType = FieldType.OBJECT_ID)
    private String ownerUserId;

    /** Normalized {@code 01XXXXXXXXX}. */
    private String phone;

    /** Business contact email (optional). */
    private String email;

    private Address address;

    private String facebookPageUrl;

    /** {@code null} = no logo. */
    private ShopLogo logo;

    private ShopStatus status;

    private ShopSettings settings = ShopSettings.defaults();

    /**
     * Last used order number. Incremented atomically when an order is created. Settings updates
     * never write this field (they use a targeted {@code $set}), so they cannot reset it.
     */
    private long orderSeq;

    @Version
    private Long version;
}

package com.sellerdesk.customer.model;

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
 * A customer of a shop (docs/modules/CUSTOMER.md §2).
 *
 * <p><b>Saved as a whole only once, on create.</b> Later changes are targeted updates in
 * {@code CustomerQueryRepository}: Order changes {@code stats} with {@code $inc} at any moment, and
 * a full save would write old counters back.
 *
 * <p>Indexes all start with {@code shopId} (docs/SCHEMA.md §10). {@code shopId_phone} is unique:
 * one customer per phone per shop. {@code phoneKeys} / {@code phoneKeysReversed} hold the main
 * and alternative phone (reversed for "ends with" search) and are multikey indexes.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
@Document("customers")
@CompoundIndexes({
        @CompoundIndex(name = "shopId_phone", def = "{'shopId': 1, 'phone': 1}", unique = true),
        @CompoundIndex(name = "shopId_status_name", def = "{'shopId': 1, 'status': 1, 'nameLower': 1}"),
        @CompoundIndex(name = "shopId_status_createdAt", def = "{'shopId': 1, 'status': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "shopId_phoneKeys", def = "{'shopId': 1, 'phoneKeys': 1}"),
        @CompoundIndex(name = "shopId_phoneKeysReversed", def = "{'shopId': 1, 'phoneKeysReversed': 1}"),
        @CompoundIndex(name = "shopId_tagKeys", def = "{'shopId': 1, 'tagKeys': 1}")
})
public class Customer extends AuditableDocument {

    @Id
    private String id;

    @Field(targetType = FieldType.OBJECT_ID)
    private String shopId;

    private String name;

    /** Lowercase {@link #name}, for prefix search and sorting. */
    private String nameLower;

    /** {@code 01XXXXXXXXX} (BdPhone). Unique per shop. */
    private String phone;

    /** {@code 01XXXXXXXXX} or {@code null}. Not unique. */
    private String altPhone;

    /** {@code [phone, altPhone?]} for prefix search. */
    private List<String> phoneKeys = new ArrayList<>();

    /** The same numbers reversed, for "ends with" search through an index. */
    private List<String> phoneKeysReversed = new ArrayList<>();

    private String facebookProfileUrl;

    /** 0–5; exactly one {@code isDefault} when not empty. */
    private List<CustomerAddress> addresses = new ArrayList<>();

    /**
     * Incremented by every address change. Address updates are "compare and set" on this number,
     * so two people changing addresses at the same time never overwrite each other. Not exposed.
     */
    private int addressRevision;

    private String note;

    /** As typed (trimmed), 0–10. */
    private List<String> tags = new ArrayList<>();

    /** Lowercase {@link #tags}, for filter and tag list. */
    private List<String> tagKeys = new ArrayList<>();

    private CustomerStatus status;

    private CustomerStats stats = CustomerStats.empty();

    /** Guards profile edits and status changes only (docs/modules/CUSTOMER.md §1). */
    @Version
    private Long version;

    @Field(targetType = FieldType.OBJECT_ID)
    private String createdBy;
}

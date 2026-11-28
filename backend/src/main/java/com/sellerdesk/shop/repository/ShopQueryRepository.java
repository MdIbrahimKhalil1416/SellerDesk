package com.sellerdesk.shop.repository;

import com.sellerdesk.shop.model.Shop;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * Targeted writes to one shop document.
 *
 * <p>Why not {@code shopRepository.save(shop)}: {@code save} writes the <b>whole</b> document it
 * loaded. If an order was created between the load and the save, its {@code $inc} of
 * {@code orderSeq} would be overwritten with the old value, and the next order would reuse a
 * number. A {@code $set} of only the changed fields cannot do that.
 *
 * <p>{@code shopId} must come from the logged-in user (TenantContext), never from the request.
 */
@Repository
@RequiredArgsConstructor
public class ShopQueryRepository {

    private final MongoTemplate mongoTemplate;

    /**
     * Applies {@code update} (plus {@code updatedAt} and {@code version + 1}) and returns the shop
     * <b>after</b> the change, or empty if the shop does not exist.
     */
    public Optional<Shop> updateAndGet(String shopId, Update update, Instant now) {
        return findAndModify(shopId, update, now, true);
    }

    /**
     * Like {@link #updateAndGet}, but returns the shop <b>before</b> the change. Used for the logo:
     * the old document tells exactly which file was replaced, even if two uploads run at once.
     */
    public Optional<Shop> updateAndGetPrevious(String shopId, Update update, Instant now) {
        return findAndModify(shopId, update, now, false);
    }

    /**
     * Takes the next order number: {@code $inc orderSeq} in one atomic step and returns the shop
     * <b>after</b> it (new {@code orderSeq} + current settings). Two parallel calls always get two
     * different numbers. Does not bump {@code version} or {@code updatedAt}: taking a number is not
     * a settings change. Empty if the shop does not exist.
     */
    public Optional<Shop> incrementOrderSeq(String shopId) {
        Query query = new Query(Criteria.where("_id").is(shopId));
        query.fields().include("orderSeq").include("settings");
        return Optional.ofNullable(mongoTemplate.findAndModify(query, new Update().inc("orderSeq", 1),
                FindAndModifyOptions.options().returnNew(true), Shop.class));
    }

    private Optional<Shop> findAndModify(String shopId, Update update, Instant now, boolean returnNew) {
        // Find-and-modify is one atomic step on the server: no other write can slip in between.
        update.set("updatedAt", now).inc("version", 1);
        return Optional.ofNullable(mongoTemplate.findAndModify(
                new Query(Criteria.where("_id").is(shopId)), update,
                FindAndModifyOptions.options().returnNew(returnNew), Shop.class));
    }
}

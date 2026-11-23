package com.sellerdesk.auth.repository;

import com.sellerdesk.auth.model.User;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.data.repository.Repository;

/**
 * Lookups needed <b>before</b> we know the shop: login by email, and the JWT filter loading the
 * token's user by id. That is why these methods have no {@code shopId} parameter.
 *
 * <p>Only {@code auth} code may use this repository. Staff management goes through
 * {@code UserService}, which only uses the shop-scoped {@link #findByIdAndShopId} for shop data.
 *
 * <p>Extends {@link Repository}, not {@code MongoRepository}, so no unscoped
 * {@code findAll()} / {@code deleteById()} exist (docs/SECURITY.md §4, layer 3).
 *
 * <h2>Atomic updates</h2>
 * The {@code @Update} methods change single fields on the server in one step, instead of
 * "load document, change field, save whole document". Two reasons:
 * <ul>
 *   <li>No false {@code 409}: {@code save()} checks {@code @Version}, so two parallel logins of
 *       the same user would conflict although they only touch {@code lastLoginAt}.</li>
 *   <li>No lost revocation: methods that change security fields ({@code tokenVersion},
 *       {@code passwordHash}, {@code status}) also {@code $inc} {@code version}. Any code that
 *       loaded the user earlier and then calls {@code save()} gets an optimistic-lock error
 *       instead of silently writing the old tokenVersion back (which would revive logged-out tokens).
 *       {@code lastLoginAt} is not security-relevant, so it does not bump the version.</li>
 * </ul>
 * Each returns the number of documents changed (0 or 1).
 */
public interface UserRepository extends Repository<User, String> {

    Optional<User> findById(String id);

    /** Staff management: a user of this shop only. Another shop's user → empty (→ 404). */
    Optional<User> findByIdAndShopId(String id, String shopId);

    /** {@code email} must already be lowercase and trimmed. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    User save(User user);

    @Query("{ '_id': ?0 }")
    @Update("{ '$set': { 'lastLoginAt': ?1 } }")
    long updateLastLoginAt(String userId, Instant at);

    /** PENDING_VERIFICATION → ACTIVE. 0 if the user was not pending (anymore). */
    @Query("{ '_id': ?0, 'status': 'PENDING_VERIFICATION' }")
    @Update("{ '$set': { 'status': 'ACTIVE', 'lastLoginAt': ?1, 'updatedAt': ?1 }, '$inc': { 'version': 1 } }")
    long activatePendingUser(String userId, Instant at);

    /** New password, ACTIVE, all tokens revoked. 0 if the user is DISABLED. */
    @Query("{ '_id': ?0, 'status': { '$ne': 'DISABLED' } }")
    @Update("{ '$set': { 'passwordHash': ?1, 'status': 'ACTIVE', 'updatedAt': ?2 }, '$inc': { 'tokenVersion': 1, 'version': 1 } }")
    long resetPassword(String userId, String passwordHash, Instant at);

    /** Logout: every access and refresh token issued so far stops working. */
    @Query("{ '_id': ?0 }")
    @Update("{ '$set': { 'updatedAt': ?1 }, '$inc': { 'tokenVersion': 1, 'version': 1 } }")
    long incrementTokenVersion(String userId, Instant at);
}

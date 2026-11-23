package com.sellerdesk.staff.repository;

import com.sellerdesk.staff.model.InvitationStatus;
import com.sellerdesk.staff.model.StaffInvitation;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Staff invitation queries. Every method takes {@code shopId}, except {@link #findByTokenHash}.
 *
 * <p>Why {@link #findByTokenHash} has no shop: it serves the public accept page, where nobody is
 * logged in yet. The 256-bit random token <i>is</i> the authorization, and the invitation found
 * decides the shop. The request never chooses one.
 *
 * <p>Extends {@link Repository}, not {@code MongoRepository}: no unscoped {@code findById} /
 * {@code findAll} / {@code deleteById} (docs/SECURITY.md §4, layer 3).
 */
public interface StaffInvitationRepository extends Repository<StaffInvitation, String> {

    Optional<StaffInvitation> findByIdAndShopId(String id, String shopId);

    /** {@code email} must already be lowercase and trimmed. */
    Optional<StaffInvitation> findByShopIdAndEmailAndStatus(String shopId, String email, InvitationStatus status);

    Optional<StaffInvitation> findByTokenHash(String tokenHash);

    StaffInvitation save(StaffInvitation invitation);
}

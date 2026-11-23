package com.sellerdesk.staff.service;

import com.sellerdesk.auth.model.User;
import com.sellerdesk.auth.service.AuthService;
import com.sellerdesk.auth.service.UserService;
import com.sellerdesk.common.error.FieldValidationException;
import com.sellerdesk.common.security.AppUserDetails;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import com.sellerdesk.common.util.Normalize;
import com.sellerdesk.shop.dto.ShopSummary;
import com.sellerdesk.shop.model.ShopStatus;
import com.sellerdesk.shop.service.ShopService;
import com.sellerdesk.staff.dto.AcceptInvitationRequest;
import com.sellerdesk.staff.dto.InvitationPreviewResponse;
import com.sellerdesk.staff.dto.InvitationResponse;
import com.sellerdesk.staff.dto.InviteStaffRequest;
import com.sellerdesk.staff.model.InvitationStatus;
import com.sellerdesk.staff.model.StaffInvitation;
import com.sellerdesk.staff.repository.StaffInvitationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Staff invitations: create, resend, cancel (logged-in, {@code STAFF_MANAGE}) and preview /
 * accept (public, by token). docs/modules/STAFF.md §3.4–3.5.
 *
 * <p>The shop always comes from the logged-in user (create/resend/cancel) or from the invitation
 * found by its token (preview/accept), never from the request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvitationService {

    private final StaffInvitationRepository invitationRepository;
    private final UserService userService;
    private final AuthService authService;
    private final ShopService shopService;
    private final CurrentUser currentUser;
    private final PasswordEncoder passwordEncoder;
    private final TransactionOperations transactions;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // ==== Create =========================================================================

    /**
     * Invites an email. An open (non-expired) invitation for it → {@code 409 ALREADY_INVITED};
     * an expired one is reused with the new role, permissions and a new link.
     */
    public InvitationResponse invite(InviteStaffRequest request) {
        AppUserDetails editor = currentUser.details();
        String shopId = currentUser.shopId();
        String email = Normalize.email(request.email());
        Role role = StaffRules.parseStaffRole(request.role(), "role");
        Set<Permission> permissions = request.permissions() == null
                ? EnumSet.copyOf(role.defaultPermissions())
                : StaffRules.parsePermissions(request.permissions());
        StaffRules.checkGrantable(editor, Set.of(), permissions);

        userService.findByEmail(email).ifPresent(existing -> {
            throw shopId.equals(existing.getShopId())
                    ? StaffErrors.alreadyAMember()
                    : StaffErrors.emailAlreadyRegistered();
        });

        Instant now = clock.instant();
        StaffInvitation invitation = invitationRepository
                .findByShopIdAndEmailAndStatus(shopId, email, InvitationStatus.PENDING)
                .map(open -> {
                    if (!open.isExpired(now)) {
                        throw StaffErrors.alreadyInvited();
                    }
                    return open; // expired: reuse it instead of a 409
                })
                .orElseGet(() -> newInvitation(shopId, email));
        invitation.setRole(role);
        invitation.setPermissions(permissions);
        invitation.setInvitedBy(editor.userId());

        try {
            StaffInvitation saved = saveAndSend(invitation, now, shopId, editor.userId());
            log.info("Shop {}: invitation {} created by user {}", shopId, saved.getId(), editor.userId());
            return InvitationResponse.from(saved);
        } catch (DuplicateKeyException e) {
            // Two invites for the same email at the same moment: the partial unique index decided.
            throw StaffErrors.alreadyInvited();
        }
    }

    private static StaffInvitation newInvitation(String shopId, String email) {
        StaffInvitation invitation = new StaffInvitation();
        invitation.setShopId(shopId);
        invitation.setEmail(email);
        invitation.setStatus(InvitationStatus.PENDING);
        invitation.setSendCount(0);
        invitation.setRecentSends(new ArrayList<>());
        return invitation;
    }

    // ==== Resend / cancel ================================================================

    /** New token (the old link stops working), new expiry. Works for expired invitations too. */
    public InvitationResponse resend(String invitationId) {
        String shopId = currentUser.shopId();
        StaffInvitation invitation = loadPending(invitationId, shopId);
        Instant now = clock.instant();
        StaffRules.checkCanResend(invitation, now);
        return InvitationResponse.from(saveAndSend(invitation, now, shopId, currentUser.userId()));
    }

    /** {@code CANCELLED}: the link stops working. The document stays for history. */
    public void cancel(String invitationId) {
        String shopId = currentUser.shopId();
        StaffInvitation invitation = loadPending(invitationId, shopId);
        invitation.setStatus(InvitationStatus.CANCELLED);
        invitationRepository.save(invitation);
        log.info("Shop {}: invitation {} cancelled by user {}", shopId, invitationId, currentUser.userId());
    }

    private StaffInvitation loadPending(String invitationId, String shopId) {
        StaffInvitation invitation = invitationRepository.findByIdAndShopId(invitationId, shopId)
                .orElseThrow(StaffErrors::invitationNotFound);
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw StaffErrors.invitationNotPending();
        }
        return invitation;
    }

    /**
     * New token + expiry + send counters, saved in a transaction that also publishes the
     * {@link StaffInvitedEvent}. The email listener runs only after the commit, so a failed save
     * never sends a link to an invitation that does not exist.
     */
    private StaffInvitation saveAndSend(StaffInvitation invitation, Instant now, String shopId, String senderId) {
        String token = InvitationTokens.newToken();
        invitation.setTokenHash(InvitationTokens.hash(token));
        invitation.setExpiresAt(now.plus(StaffInvitation.VALIDITY));
        invitation.setSendCount(invitation.getSendCount() + 1);
        invitation.setLastSentAt(now);
        invitation.setRecentSends(StaffRules.recordSend(invitation.getRecentSends(), now));

        String shopName = shopService.findSummary(shopId).map(ShopSummary::name).orElse("your shop");
        String inviterName = userService.findInShop(senderId, shopId).map(User::getName).orElse("Your shop owner");

        return transactions.execute(tx -> {
            StaffInvitation saved = invitationRepository.save(invitation);
            events.publishEvent(new StaffInvitedEvent(saved.getId(), saved.getEmail(), token, shopName,
                    inviterName, saved.getRole(), saved.getExpiresAt()));
            return saved;
        });
    }

    // ==== Public: preview / accept ========================================================

    public InvitationPreviewResponse preview(String token) {
        ValidInvitation valid = findValid(token);
        StaffInvitation invitation = valid.invitation();
        String inviterName = userService.findInShop(invitation.getInvitedBy(), invitation.getShopId())
                .map(User::getName).orElse(null);
        return new InvitationPreviewResponse(invitation.getEmail(), valid.shop().name(), invitation.getRole(),
                inviterName, invitation.getExpiresAt());
    }

    /**
     * Creates the ACTIVE user (shop, role and permissions from the invitation) and marks the
     * invitation ACCEPTED, in <b>one transaction</b>; then logs the new user in.
     */
    public AuthService.AuthResult accept(AcceptInvitationRequest request) {
        StaffInvitation invitation = findValid(request.token()).invitation();
        String email = invitation.getEmail();
        if (request.password().trim().toLowerCase(Locale.ROOT).equals(email)) {
            throw new FieldValidationException("password", "must not be the same as the email");
        }
        if (userService.findByEmail(email).isPresent()) {
            throw StaffErrors.emailAlreadyRegistered(); // e.g. another shop's invitation was accepted first
        }

        // BCrypt is slow on purpose: hash before the transaction so the transaction stays short.
        String passwordHash = passwordEncoder.encode(request.password());
        Instant now = clock.instant();
        User user;
        try {
            user = transactions.execute(tx -> {
                User created = userService.createStaffMember(new UserService.NewStaffMember(
                        invitation.getShopId(), email, request.name().trim(), passwordHash, invitation.getRole(),
                        invitation.getPermissions(), invitation.getInvitedBy(), now));
                invitation.setStatus(InvitationStatus.ACCEPTED);
                invitation.setAcceptedUserId(created.getId());
                invitationRepository.save(invitation); // @Version: a parallel resend/cancel/accept wins → rollback
                return created;
            });
        } catch (OptimisticLockingFailureException e) {
            throw StaffErrors.invalidInvitation();
        }
        log.info("Shop {}: invitation {} accepted, user {} created", invitation.getShopId(), invitation.getId(),
                user.getId());
        return authService.startSession(user);
    }

    private record ValidInvitation(StaffInvitation invitation, ShopSummary shop) {
    }

    /** Unknown, cancelled, used or of an inactive shop → INVALID_INVITATION; pending but old → INVITATION_EXPIRED. */
    private ValidInvitation findValid(String token) {
        StaffInvitation invitation = invitationRepository.findByTokenHash(InvitationTokens.hash(token))
                .filter(i -> i.getStatus() == InvitationStatus.PENDING)
                .orElseThrow(StaffErrors::invalidInvitation);
        if (invitation.isExpired(clock.instant())) {
            throw StaffErrors.invitationExpired();
        }
        ShopSummary shop = shopService.findSummary(invitation.getShopId())
                .filter(s -> s.status() == ShopStatus.ACTIVE)
                .orElseThrow(StaffErrors::invalidInvitation);
        return new ValidInvitation(invitation, shop);
    }
}

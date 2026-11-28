package com.sellerdesk.common.security;

/**
 * "Who did it" when no person did: a courier webhook, a background job (docs/modules/COURIER.md §5).
 *
 * <p>Used as the principal of the {@code SecurityContext} inside {@link SystemActorScope}. It has
 * <b>no permissions</b> and is never a JWT, so it can never call an endpoint. Its {@link #userId}
 * is a reserved ObjectId that no real user can have (real ids start with a timestamp, these with
 * zeros), so order history and stock movements can show it like a user.
 *
 * @param userId reserved id, stored in {@code statusHistory.by} / {@code stock_movements.createdBy}
 * @param name   shown instead of a user name, e.g. {@code "Courier (Mock courier)"}
 */
public record SystemActor(String userId, String name) {

    /** Background jobs (booking reconciler, tracking sweep). */
    public static final String SYSTEM_ID = "000000000000000000000001";

    /** Courier webhooks and courier status polls. */
    public static final String COURIER_ID = "000000000000000000000002";

    public static SystemActor system() {
        return new SystemActor(SYSTEM_ID, "SellerDesk");
    }

    /** @param courierName display name of the provider, e.g. {@code Mock courier} */
    public static SystemActor courier(String courierName) {
        return new SystemActor(COURIER_ID, "Courier (" + courierName + ")");
    }

    @Override
    public String toString() {
        return "SystemActor[" + name + "]";
    }
}

package com.sellerdesk.auth.service;

/**
 * "A new shop owner registered." Published inside the registration transaction; listeners marked
 * {@code @TransactionalEventListener} only receive it <b>after the commit succeeded</b>.
 */
public record UserRegisteredEvent(String userId, String shopId, String email, String name) {
}

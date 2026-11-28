package com.sellerdesk.courier.provider;

/** Outcome of a cancel call. Cancelling twice must be harmless at the provider. */
public sealed interface CancelResult {

    record Ok() implements CancelResult {
    }

    /** e.g. already picked up. */
    record Rejected(String reason) implements CancelResult {
    }

    record Unavailable(String cause) implements CancelResult {
    }
}

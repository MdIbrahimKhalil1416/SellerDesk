package com.sellerdesk.courier.provider;

/** Outcome of a status poll. */
public sealed interface TrackResult {

    /** The courier's current view, already mapped to our types. */
    record Ok(CourierUpdate update) implements TrackResult {
    }

    record Rejected(String reason) implements TrackResult {
    }

    record Unavailable(String cause) implements TrackResult {
    }
}

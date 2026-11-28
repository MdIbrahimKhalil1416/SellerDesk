package com.sellerdesk.courier.model;

/** Where a shipment event came from. */
public enum EventSource {
    /** A user action (send, cancel). */
    USER,
    /** SellerDesk itself (booking result, reconciler, notes). */
    SYSTEM,
    /** A courier webhook. */
    WEBHOOK,
    /** A status poll (track endpoint, tracking sweep). */
    POLL
}

package com.sellerdesk.customer.model;

/** Customers are archived, never deleted: orders reference them forever. */
public enum CustomerStatus {
    ACTIVE,
    ARCHIVED
}

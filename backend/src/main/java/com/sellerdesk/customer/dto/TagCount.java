package com.sellerdesk.customer.dto;

/** A tag in use (active customers) and how many customers have it; for autocomplete and filters. */
public record TagCount(String name, long customerCount) {
}

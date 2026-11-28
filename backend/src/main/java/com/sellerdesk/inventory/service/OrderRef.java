package com.sellerdesk.inventory.service;

/** The order that causes a stock movement; copied into the movement for the log. */
public record OrderRef(String orderId, String orderNumber) {
}

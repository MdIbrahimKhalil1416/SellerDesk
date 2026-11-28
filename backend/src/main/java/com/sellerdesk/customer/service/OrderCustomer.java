package com.sellerdesk.customer.service;

import com.sellerdesk.customer.dto.CustomerResponse;
import com.sellerdesk.customer.model.CustomerStatus;

/**
 * What the Order module needs from a customer: the id to reference and the data for the order's
 * snapshot. {@code defaultAddress} is {@code null} when the customer has no address.
 */
public record OrderCustomer(String customerId, String name, String phone, CustomerStatus status,
                            CustomerResponse.AddressResponse defaultAddress) {
}

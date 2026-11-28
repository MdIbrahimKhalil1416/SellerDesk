package com.sellerdesk.customer.service;

import com.sellerdesk.common.dto.AddressDto;

/**
 * The customer typed into the order form, for {@link CustomerService#findOrCreateByPhone}.
 *
 * @param phone   any accepted Bangladeshi format
 * @param address the delivery address, or {@code null}
 */
public record NewOrderCustomer(String name, String phone, AddressDto address) {
}

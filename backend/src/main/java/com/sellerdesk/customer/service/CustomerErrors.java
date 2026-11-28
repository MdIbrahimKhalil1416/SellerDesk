package com.sellerdesk.customer.service;

import com.sellerdesk.common.error.ApiError;
import com.sellerdesk.common.error.BusinessRuleException;
import com.sellerdesk.common.error.ResourceNotFoundException;
import java.util.List;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;

/** Error codes of the Customer module (docs/modules/CUSTOMER.md §4). The frontend switches on them. */
public final class CustomerErrors {

    /** Customer or address id unknown <b>or of another shop</b>: the two look the same. */
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String PHONE_ALREADY_EXISTS = "PHONE_ALREADY_EXISTS";
    public static final String ADDRESS_LIMIT_REACHED = "ADDRESS_LIMIT_REACHED";

    private CustomerErrors() {
    }

    public static ResourceNotFoundException customerNotFound() {
        return new ResourceNotFoundException(NOT_FOUND, "Customer not found");
    }

    public static ResourceNotFoundException addressNotFound() {
        return new ResourceNotFoundException(NOT_FOUND, "Address not found");
    }

    public static BusinessRuleException addressLimitReached() {
        return new BusinessRuleException(ADDRESS_LIMIT_REACHED,
                "A customer can have at most " + CustomerRules.MAX_ADDRESSES + " addresses");
    }

    /** {@code 409 PHONE_ALREADY_EXISTS} with a field error on {@code field} (usually {@code phone}). */
    public static PhoneAlreadyExistsException phoneAlreadyExists(String field, String phone) {
        return new PhoneAlreadyExistsException(field, phone);
    }

    /** Handled globally as {@code 409 CONCURRENT_MODIFICATION}. */
    public static OptimisticLockingFailureException staleVersion() {
        return new OptimisticLockingFailureException("Customer was changed by someone else");
    }

    /** The only error of this module that carries a field error with a 409. */
    public static class PhoneAlreadyExistsException extends com.sellerdesk.common.error.AppException {
        PhoneAlreadyExistsException(String field, String phone) {
            super(HttpStatus.CONFLICT, PHONE_ALREADY_EXISTS,
                    "Another customer of your shop already has the phone " + phone,
                    List.of(new ApiError.FieldError(field, "is already used by another customer")));
        }
    }
}

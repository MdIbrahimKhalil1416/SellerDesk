package com.sellerdesk.common.phone;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Checks {@link BdMobile} fields with {@link BdPhone#isValid}. */
public class BdMobileValidator implements ConstraintValidator<BdMobile, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // null or blank: "not given". Whether that is allowed is @NotBlank's job, so an empty field
        // gets one clear error ("must not be blank"), not two.
        return value == null || value.isBlank() || BdPhone.isValid(value);
    }
}

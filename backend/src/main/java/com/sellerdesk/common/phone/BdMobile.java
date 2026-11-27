package com.sellerdesk.common.phone;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean Validation: the field must be a Bangladeshi mobile number in any format {@link BdPhone}
 * accepts. {@code null} and blank are valid; add {@code @NotBlank} when the field is required.
 *
 * <p>The service still calls {@link BdPhone#normalize} before saving: this annotation only checks,
 * it does not change the value.
 */
@Documented
@Constraint(validatedBy = BdMobileValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface BdMobile {

    String message() default BdPhone.INVALID_MESSAGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

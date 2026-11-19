package com.sellerdesk.auth.dto;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * The password rules (docs/SECURITY.md §1) in one place, used wherever a password is <b>set</b>:
 * registration and password reset.
 *
 * <p>A "composed constraint": the annotations on this annotation are what actually run. Each
 * broken rule is reported with its own message (8–72 chars, one letter, one digit).
 * "Must not equal the email" needs two fields and is checked in AuthService.
 */
@NotBlank
@Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
@Pattern(regexp = "(?s).*\\p{L}.*", message = "must contain at least one letter")
@Pattern(regexp = "(?s).*\\p{Nd}.*", message = "must contain at least one digit")
@Constraint(validatedBy = {})
@Documented
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface ValidPassword {

    String message() default "invalid password";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

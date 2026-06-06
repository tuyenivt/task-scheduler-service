package com.example.taskscheduler.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Constraint that bounds the JSON-serialized size of a {@code Map} payload.
 * <p>
 * Rejects payloads whose serialized representation exceeds {@link #maxBytes()}.
 * Defends the row, GIN indexes, and WAL against unbounded growth at the API
 * boundary; a matching DB CHECK constraint provides defense-in-depth.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = JsonSizeWithinValidator.class)
public @interface JsonSizeWithin {

    int maxBytes() default 65_536;

    String message() default "serialized JSON exceeds maximum allowed size";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

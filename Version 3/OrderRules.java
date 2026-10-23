package com.example.rules.order;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/** Règles de cohérence de la commande (posée sur la classe Order par validation-rules.xml). */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = OrderRulesValidator.class)
public @interface OrderRules {
    String message() default "{rules.order.invalid}";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

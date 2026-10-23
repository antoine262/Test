package com.example.rules.user;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/** Règles de cohérence de l'utilisateur (posée sur la classe User par validation-rules.xml). */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = UserRulesValidator.class)
public @interface UserRules {
    String message() default "{rules.user.invalid}";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

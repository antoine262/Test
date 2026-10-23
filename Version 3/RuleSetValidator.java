package com.example.rules.core;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Base des validators de classe racine : exécute une liste de règles et transmet toutes les violations
 * (pas seulement la première). Une sous-classe se limite à déclarer ses règles.
 */
public abstract class RuleSetValidator<A extends Annotation, T> implements ConstraintValidator<A, T> {
    private List<Rule<T>> rules = List.of();

    /** La liste des règles de cette classe racine : ajouter une règle = ajouter une ligne ici. */
    protected abstract List<Rule<T>> createRules(RuleSettings settings);

    @Override
    public void initialize(A constraintAnnotation) {
        rules = List.copyOf(createRules(RuleSettings.load()));
    }

    @Override
    public boolean isValid(T root, ConstraintValidatorContext ctx) {
        if (root == null) return true;
        Violations violations = new Violations();
        for (Rule<T> rule : rules) rule.check(root, violations);
        if (violations.isEmpty()) return true;
        violations.writeTo(ctx);
        return false;
    }
}

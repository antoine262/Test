package com.example.rules.core;

/**
 * Une règle de validation sur un objet racine. Elle doit tolérer les valeurs absentes (null) :
 * c'est le rôle des contraintes du XSD (@NotNull...). Elle ne vérifie que la cohérence.
 */
@FunctionalInterface
public interface Rule<T> {
    void check(T root, Violations out);
}

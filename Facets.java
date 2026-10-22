package com.example.xsdval.xsd;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Facettes effectives d'un type simple, après fusion de la chaîne de restrictions
 * (la facette la plus dérivée gagne ; les pattern de niveaux différents sont combinés en ET).
 */
public record Facets(Integer length, Integer minLength, Integer maxLength,
                     BigDecimal minInclusive, BigDecimal maxInclusive,
                     BigDecimal minExclusive, BigDecimal maxExclusive,
                     Integer totalDigits, Integer fractionDigits,
                     List<String> patternSteps, List<String> enumeration,
                     List<String> issues) {

    public boolean hasLength() { return length != null || minLength != null || maxLength != null; }

    public boolean hasNumeric() {
        return minInclusive != null || maxInclusive != null || minExclusive != null
                || maxExclusive != null || totalDigits != null || fractionDigits != null;
    }

    public boolean isEmpty() {
        return !hasLength() && !hasNumeric() && patternSteps.isEmpty() && enumeration.isEmpty();
    }

    public boolean onlyEnumeration() {
        return !enumeration.isEmpty() && patternSteps.isEmpty() && !hasLength() && !hasNumeric();
    }

    /** Regex Java unique regroupant patterns et enumération (null si aucune). */
    public String regex() {
        List<String> all = new ArrayList<>(patternSteps);
        if (!enumeration.isEmpty()) {
            all.add("(?:" + String.join("|", enumeration.stream().map(Facets::quote).toList()) + ")");
        }
        if (all.isEmpty()) return null;
        String acc = all.get(0);
        for (int i = 1; i < all.size(); i++) {
            // ET logique de deux regex en "full match"
            acc = "(?=(?:" + acc + ")\\z)(?:" + all.get(i) + ")";
        }
        return acc;
    }

    private static String quote(String lit) {
        StringBuilder sb = new StringBuilder();
        for (char c : lit.toCharArray()) {
            if ("\\.[]{}()*+-?^$|".indexOf(c) >= 0) sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }
}

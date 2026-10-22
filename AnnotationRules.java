package com.example.xjcval;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Consumer;

/** Table de correspondance occurrences / facettes XSD -> annotations Jakarta Validation. */
final class AnnotationRules {
    private AnnotationRules() {}

    private static final Set<String> INTEGRAL = Set.of("byte", "short", "int", "long", "Byte", "Short", "Integer", "Long");
    private static final Set<String> BIG = Set.of("BigDecimal", "BigInteger");
    private static final Set<String> FLOATING = Set.of("float", "double", "Float", "Double");

    record Spec(Class<? extends Annotation> type, Map<String, Object> params) {
        static Spec of(Class<? extends Annotation> t, Object... kv) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
            return new Spec(t, m);
        }
    }

    /** maxOccurs : -1 = unbounded. javaType : nom simple (type d'élément pour une liste). */
    record Input(boolean collection, boolean required, int minOccurs, int maxOccurs,
                 boolean complex, Facets facets, String javaType, boolean primitive) {}

    static List<Spec> compute(Input in, Consumer<String> warn) {
        List<Spec> out = new ArrayList<>();

        if (in.collection()) {
            if (in.required() && in.minOccurs() >= 1) out.add(Spec.of(NotEmpty.class));
            List<Object> kv = new ArrayList<>();
            if (in.required() && in.minOccurs() > 1) { kv.add("min"); kv.add(in.minOccurs()); }
            if (in.maxOccurs() >= 0) { kv.add("max"); kv.add(in.maxOccurs()); }
            if (!kv.isEmpty()) out.add(Spec.of(Size.class, kv.toArray()));
        } else if (in.required() && !in.primitive()) {
            out.add(Spec.of(NotNull.class));
        }

        if (in.complex()) out.add(Spec.of(Valid.class));

        Facets fc = in.facets();
        if (fc != null && !fc.isEmpty()) {
            if (in.collection()) {
                if (!fc.onlyEnumeration()) {
                    warn.accept("facettes des éléments de liste non appliquées (CodeModel ne gère pas les annotations de type)");
                }
            } else {
                facets(fc, in.javaType(), out, warn);
            }
        }
        return out;
    }

    private static void facets(Facets fc, String type, List<Spec> out, Consumer<String> warn) {
        if (type.equals("String")) {
            Integer lo = fc.length() != null ? fc.length() : fc.minLength();
            Integer hi = fc.length() != null ? fc.length() : fc.maxLength();
            List<Object> kv = new ArrayList<>();
            if (lo != null && lo > 0) { kv.add("min"); kv.add(lo); }
            if (hi != null) { kv.add("max"); kv.add(hi); }
            if (!kv.isEmpty()) out.add(Spec.of(Size.class, kv.toArray()));
            String regex = fc.regex();
            if (regex != null) out.add(Spec.of(Pattern.class, "regexp", regex));
            if (fc.hasNumeric()) warn.accept("bornes numériques ignorées sur un String");
            return;
        }

        boolean integral = INTEGRAL.contains(type), big = BIG.contains(type), floating = FLOATING.contains(type);
        if (!integral && !big && !floating) {
            if (!fc.onlyEnumeration()) warn.accept("facettes ignorées pour le type Java " + type);
            return;
        }

        BigDecimal lo = fc.minInclusive(), hi = fc.maxInclusive();
        boolean loInc = true, hiInc = true;
        if (fc.minExclusive() != null && (lo == null || fc.minExclusive().compareTo(lo) >= 0)) { lo = fc.minExclusive(); loInc = false; }
        if (fc.maxExclusive() != null && (hi == null || fc.maxExclusive().compareTo(hi) <= 0)) { hi = fc.maxExclusive(); hiInc = false; }
        if (lo != null) out.add(bound(true, lo, loInc, integral));
        if (hi != null) out.add(bound(false, hi, hiInc, integral));

        Integer total = fc.totalDigits(), frac = fc.fractionDigits();
        if (total != null || frac != null) {
            if (floating) {
                warn.accept("@Digits non supporté sur float/double");
            } else if (integral || type.equals("BigInteger")) {
                if (total != null) out.add(Spec.of(Digits.class, "integer", total, "fraction", 0));
            } else {
                int fraction = frac != null ? frac : total;
                int integer = total != null ? Math.max(total - (frac != null ? frac : 0), 0) : 1000;
                out.add(Spec.of(Digits.class, "integer", integer, "fraction", fraction));
            }
        }
    }

    private static Spec bound(boolean lower, BigDecimal v, boolean inclusive, boolean integral) {
        if (integral) {
            BigDecimal n = lower
                    ? (inclusive ? v.setScale(0, RoundingMode.CEILING) : v.setScale(0, RoundingMode.FLOOR).add(BigDecimal.ONE))
                    : (inclusive ? v.setScale(0, RoundingMode.FLOOR) : v.setScale(0, RoundingMode.CEILING).subtract(BigDecimal.ONE));
            try {
                return Spec.of(lower ? Min.class : Max.class, "value", n.longValueExact());
            } catch (ArithmeticException tooBig) {
                // hors plage d'un long : retombe sur @DecimalMin/@DecimalMax
            }
        }
        return inclusive
                ? Spec.of(lower ? DecimalMin.class : DecimalMax.class, "value", v.toPlainString())
                : Spec.of(lower ? DecimalMin.class : DecimalMax.class, "value", v.toPlainString(), "inclusive", false);
    }
}

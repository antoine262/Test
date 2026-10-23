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

    /**
     * javaList : le champ Java est une List. repeated : maxOccurs > 1 dans le XSD.
     * maxOccurs : -1 = unbounded. javaType : nom simple (type des éléments si javaList).
     */
    record Input(boolean javaList, boolean repeated, boolean required, int minOccurs, int maxOccurs,
                 boolean complex, Facets facets, String javaType, boolean primitive) {}

    /** onField : sur le champ ; onElement : sur l'argument de type (List<@Size String>). */
    record Result(List<Spec> onField, List<Spec> onElement) {}

    static Result compute(Input in, Consumer<String> warn) {
        List<Spec> field = new ArrayList<>(), elem = new ArrayList<>();
        Facets fc = in.facets();

        if (in.javaList()) {
            if (in.repeated()) {
                if (in.required() && in.minOccurs() >= 1) field.add(Spec.of(NotEmpty.class));
                addSize(field, in.required() && in.minOccurs() > 1 ? in.minOccurs() : null,
                        in.maxOccurs() >= 0 ? in.maxOccurs() : null);
            } else if (in.required()) {
                field.add(Spec.of(NotNull.class));             // élément unique de type xs:list
            }
            if (fc != null && !fc.isEmpty()) {
                if (fc.isList()) {
                    if (in.repeated()) {
                        warn.accept("liste de xs:list non couverte");
                    } else {
                        Integer lo = fc.length() != null ? fc.length() : fc.minLength();
                        Integer hi = fc.length() != null ? fc.length() : fc.maxLength();
                        addSize(field, lo != null && lo > 0 ? lo : null, hi);
                        facets(fc.itemFacets(), in.javaType(), elem, warn);
                    }
                } else if (in.repeated()) {
                    facets(fc, in.javaType(), elem, warn);     // facettes appliquées à chaque élément
                } else {
                    warn.accept("facettes ignorées sur un champ liste inattendu");
                }
            }
        } else {
            if (in.required() && !in.primitive()) field.add(Spec.of(NotNull.class));
            facets(fc, in.javaType(), field, warn);
        }

        if (in.complex()) field.add(Spec.of(Valid.class));
        return new Result(field, elem);
    }

    private static void addSize(List<Spec> out, Integer min, Integer max) {
        List<Object> kv = new ArrayList<>();
        if (min != null) { kv.add("min"); kv.add(min); }
        if (max != null) { kv.add("max"); kv.add(max); }
        if (!kv.isEmpty()) out.add(Spec.of(Size.class, kv.toArray()));
    }

    private static void facets(Facets fc, String type, List<Spec> out, Consumer<String> warn) {
        if (fc == null || fc.isEmpty()) return;
        String regex = fc.regex();

        boolean string = type.equals("String"), binary = type.equals("byte[]");
        if (string || binary) {
            Integer lo = fc.length() != null ? fc.length() : fc.minLength();
            Integer hi = fc.length() != null ? fc.length() : fc.maxLength();
            addSize(out, lo != null && lo > 0 ? lo : null, hi);
            if (regex != null) {
                if (string) out.add(Spec.of(Pattern.class, "regexp", regex));
                else warn.accept("pattern/enumeration ignorés sur " + type);
            }
            if (fc.hasNumeric()) warn.accept("bornes numériques ignorées sur " + type);
            return;
        }

        boolean integral = INTEGRAL.contains(type), big = BIG.contains(type), floating = FLOATING.contains(type);
        if (!integral && !big && !floating) {
            if (!fc.onlyEnumeration()) warn.accept("facettes ignorées pour le type Java " + type);   // enum JAXB : rien à faire
            return;
        }

        if (fc.hasLength()) warn.accept("length/minLength/maxLength ignorés sur " + type);
        if (regex != null) warn.accept("pattern/enumeration non traduits sur " + type);

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

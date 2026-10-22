package com.example.xsdval.rules;

import com.example.xsdval.Report;
import com.example.xsdval.model.XsdModel.FieldInfo;
import com.example.xsdval.xsd.Facets;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Table de correspondance facettes / occurrences XSD -> annotations Jakarta Validation. */
public final class AnnotationRules {
    private AnnotationRules() {}

    private static final Set<String> INTEGRAL = Set.of("byte", "short", "int", "long", "Byte", "Short", "Integer", "Long");
    private static final Set<String> BIG = Set.of("BigDecimal", "BigInteger");
    private static final Set<String> FLOATING = Set.of("float", "double", "Float", "Double");

    /** onField : sur le champ ; onElement : sur l'argument de type (List&lt;@Size String&gt;). */
    public record Result(List<Ann> onField, List<Ann> onElement) {}

    public static Result compute(FieldInfo f, String javaType, boolean list, boolean primitive,
                                 Report report, String where) {
        List<Ann> field = new ArrayList<>(), elem = new ArrayList<>();

        if (list) {
            if (f.minOccurs() >= 1) field.add(Ann.of("NotEmpty"));
            List<String> p = new ArrayList<>();
            if (f.minOccurs() > 1) p.add("min = " + f.minOccurs());
            if (f.maxOccurs() >= 0) p.add("max = " + f.maxOccurs());
            if (!p.isEmpty()) field.add(Ann.of("Size", p.toArray(String[]::new)));
        } else if (f.required() && !primitive) {
            field.add(Ann.of("NotNull"));
        }

        if (f.complexKey() != null) field.add(Ann.valid());
        if (f.facets() != null) facets(f.facets(), javaType, list ? elem : field, report, where);
        return new Result(field, elem);
    }

    private static void facets(Facets fc, String type, List<Ann> out, Report report, String where) {
        if (fc.isEmpty()) return;

        if (type.equals("String")) {
            Integer lo = fc.length() != null ? fc.length() : fc.minLength();
            Integer hi = fc.length() != null ? fc.length() : fc.maxLength();
            List<String> p = new ArrayList<>();
            if (lo != null && lo > 0) p.add("min = " + lo);
            if (hi != null) p.add("max = " + hi);
            if (!p.isEmpty()) out.add(Ann.of("Size", p.toArray(String[]::new)));
            String regex = fc.regex();
            if (regex != null) out.add(Ann.of("Pattern", "regexp = " + Ann.lit(regex)));
            if (fc.hasNumeric()) report.warn(where + ": bornes numériques ignorées sur un String");
            return;
        }

        boolean integral = INTEGRAL.contains(type), big = BIG.contains(type), floating = FLOATING.contains(type);
        if (!integral && !big && !floating) {
            if (!fc.onlyEnumeration()) {   // enum JAXB : rien à faire
                report.warn(where + ": facettes ignorées pour le type Java " + type);
            }
            return;
        }

        // Borne basse / haute effective (la plus stricte gagne)
        BigDecimal lo = fc.minInclusive(), hi = fc.maxInclusive();
        boolean loInc = true, hiInc = true;
        if (fc.minExclusive() != null && (lo == null || fc.minExclusive().compareTo(lo) >= 0)) { lo = fc.minExclusive(); loInc = false; }
        if (fc.maxExclusive() != null && (hi == null || fc.maxExclusive().compareTo(hi) <= 0)) { hi = fc.maxExclusive(); hiInc = false; }

        if (lo != null) out.add(bound(true, lo, loInc, integral));
        if (hi != null) out.add(bound(false, hi, hiInc, integral));

        Integer total = fc.totalDigits(), frac = fc.fractionDigits();
        if (total != null || frac != null) {
            if (floating) {
                report.warn(where + ": @Digits non supporté sur float/double");
            } else if (integral || type.equals("BigInteger")) {
                if (total != null) out.add(Ann.of("Digits", "integer = " + total, "fraction = 0"));
            } else {
                int fraction = frac != null ? frac : total;
                int integer = total != null ? Math.max(total - (frac != null ? frac : 0), 0) : 1000;
                out.add(Ann.of("Digits", "integer = " + integer, "fraction = " + fraction));
            }
        }
    }

    private static Ann bound(boolean lower, BigDecimal v, boolean inclusive, boolean integral) {
        if (integral) {
            BigDecimal n = lower
                    ? (inclusive ? v.setScale(0, RoundingMode.CEILING) : v.setScale(0, RoundingMode.FLOOR).add(BigDecimal.ONE))
                    : (inclusive ? v.setScale(0, RoundingMode.FLOOR) : v.setScale(0, RoundingMode.CEILING).subtract(BigDecimal.ONE));
            try {
                long l = n.longValueExact();
                String lit = (l > Integer.MAX_VALUE || l < Integer.MIN_VALUE) ? l + "L" : String.valueOf(l);
                return Ann.of(lower ? "Min" : "Max", "value = " + lit);
            } catch (ArithmeticException tooBig) {
                // dépasse un long : retombe sur @DecimalMin/@DecimalMax
            }
        }
        List<String> p = new ArrayList<>();
        p.add("value = " + Ann.lit(v.toPlainString()));
        if (!inclusive) p.add("inclusive = false");
        return Ann.of(lower ? "DecimalMin" : "DecimalMax", p.toArray(String[]::new));
    }
}

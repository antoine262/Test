package com.example.xjcval;

import com.sun.xml.xsom.XSFacet;
import com.sun.xml.xsom.XSSimpleType;

import javax.xml.XMLConstants;
import java.math.BigDecimal;
import java.util.*;

/** Fusionne les facettes d'une chaîne de restrictions XSD (la plus dérivée gagne). */
final class FacetResolver {
    private static final String XS = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    /** Types prédéfinis dont les bornes ont une vraie valeur de validation. */
    private static final Set<String> NUMERIC_BUILTINS = Set.of(
            "positiveInteger", "nonNegativeInteger", "negativeInteger", "nonPositiveInteger",
            "unsignedByte", "unsignedShort", "unsignedInt", "unsignedLong");

    private FacetResolver() {}

    static Facets resolve(XSSimpleType st) {
        Map<String, String> single = new HashMap<>();
        List<String> steps = new ArrayList<>(), enums = new ArrayList<>(), issues = new ArrayList<>();
        boolean enumDone = false;

        for (XSSimpleType t = st; t != null; t = t.getSimpleBaseType()) {
            boolean builtin = XS.equals(t.getTargetNamespace());
            if (!builtin && t.isList()) { issues.add("xs:list non couvert"); break; }
            if (!builtin && t.isUnion()) { issues.add("xs:union non couvert"); break; }
            if (!t.isRestriction()) continue;

            boolean numericOk = builtin && NUMERIC_BUILTINS.contains(t.getName());
            List<String> stepPatterns = new ArrayList<>(), stepEnums = new ArrayList<>();
            for (Iterator<? extends XSFacet> it = t.asRestriction().iterateDeclaredFacets(); it.hasNext(); ) {
                XSFacet f = it.next();
                String n = f.getName(), v = f.getValue().value;
                if (XSFacet.FACET_PATTERN.equals(n)) {
                    if (!builtin) stepPatterns.add(XsdRegexConverter.toJava(v));
                } else if (XSFacet.FACET_ENUMERATION.equals(n)) {
                    if (!builtin) stepEnums.add(v);
                } else if (!builtin || numericOk) {
                    single.putIfAbsent(n, v);
                }
            }
            if (!stepPatterns.isEmpty()) {
                steps.add(String.join("|", stepPatterns.stream().map(p -> "(?:" + p + ")").toList()));
            }
            if (!enumDone && !stepEnums.isEmpty()) { enums.addAll(stepEnums); enumDone = true; }
        }
        return new Facets(
                integer(single, XSFacet.FACET_LENGTH), integer(single, XSFacet.FACET_MINLENGTH),
                integer(single, XSFacet.FACET_MAXLENGTH),
                decimal(single, XSFacet.FACET_MININCLUSIVE, issues), decimal(single, XSFacet.FACET_MAXINCLUSIVE, issues),
                decimal(single, XSFacet.FACET_MINEXCLUSIVE, issues), decimal(single, XSFacet.FACET_MAXEXCLUSIVE, issues),
                integer(single, XSFacet.FACET_TOTALDIGITS), integer(single, XSFacet.FACET_FRACTIONDIGITS),
                steps, enums, issues);
    }

    private static Integer integer(Map<String, String> m, String k) {
        String v = m.get(k);
        return v == null ? null : Integer.valueOf(v.trim());
    }

    private static BigDecimal decimal(Map<String, String> m, String k, List<String> issues) {
        String v = m.get(k);
        if (v == null) return null;
        try { return new BigDecimal(v.trim()); }
        catch (NumberFormatException e) { issues.add("borne non numérique ignorée (" + k + "=" + v + ")"); return null; }
    }
}

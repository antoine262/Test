public record Facets(Integer length, Integer minLength, Integer maxLength,
                     BigDecimal minInclusive, BigDecimal maxInclusive,
                     BigDecimal minExclusive, BigDecimal maxExclusive,
                     Integer totalDigits, Integer fractionDigits,
                     List<String> patternSteps, List<String> enumeration,
                     List<String> issues,
                     Facets itemFacets) {          // non null si le type est un xs:list

    /** Vrai pour un xs:list : length/min/max portent sur la liste, itemFacets sur ses éléments. */
    public boolean isList() { return itemFacets != null; }

    public boolean isEmpty() {
        return !hasLength() && !hasNumeric() && patternSteps.isEmpty()
                && enumeration.isEmpty() && itemFacets == null;
    }
    // le reste du fichier (hasLength, hasNumeric, onlyEnumeration, regex, quote) est inchangé


package com.example.xjcval;

import com.sun.xml.xsom.XSFacet;
import com.sun.xml.xsom.XSSimpleType;

import javax.xml.XMLConstants;
import java.math.BigDecimal;
import java.util.*;

/** Fusionne les facettes d'une chaîne de restrictions XSD (la plus dérivée gagne). */
final class FacetResolver {
    private static final String XS = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    private static final Set<String> NUMERIC_BUILTINS = Set.of(
            "positiveInteger", "nonNegativeInteger", "negativeInteger", "nonPositiveInteger",
            "unsignedByte", "unsignedShort", "unsignedInt", "unsignedLong");

    private FacetResolver() {}

    static Facets resolve(XSSimpleType st) {
        Map<String, String> single = new HashMap<>();
        List<String> steps = new ArrayList<>(), enums = new ArrayList<>(), issues = new ArrayList<>();
        Facets itemFacets = null;
        boolean enumDone = false;

        for (XSSimpleType t = st; t != null; t = t.getSimpleBaseType()) {
            boolean builtin = XS.equals(t.getTargetNamespace());

            if (t.isList()) {                                   // xs:list : facettes des items à part
                itemFacets = resolve(t.asList().getItemType());
                if (!steps.isEmpty() || !enums.isEmpty()) {
                    issues.add("pattern/enumeration sur un xs:list non couverts");
                    steps.clear();
                    enums.clear();
                }
                break;
            }
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
                steps, enums, issues, itemFacets);
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

package com.example.xjcval;

import com.sun.codemodel.*;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** JClass qui s'imprime avec des annotations de type : « @Size(max = 20) String ». */
final class AnnotatedClass extends JClass {
    private final JClass base;
    private final List<JGenerable> annotations;

    AnnotatedClass(JClass base, List<JGenerable> annotations) {
        super(base.owner());
        this.base = base;
        this.annotations = annotations;
    }

    @Override public String name() { return base.name(); }
    @Override public String fullName() { return base.fullName(); }
    @Override public JPackage _package() { return base._package(); }
    @Override public JClass _extends() { return base._extends(); }
    @Override public Iterator<JClass> _implements() { return base._implements(); }
    @Override public boolean isInterface() { return base.isInterface(); }
    @Override public boolean isAbstract() { return base.isAbstract(); }
    @Override protected JClass substituteParams(JTypeVar[] variables, List<JClass> bindings) { return this; }

    @Override
    public void generate(JFormatter f) {
        for (JGenerable a : annotations) f.g(a);
        f.t(base);
    }

    /** Écrit une annotation : @Nom(cle = valeur, ...). */
    static final class TypeAnnotation implements JGenerable {
        private final JClass annotation;
        private final Map<String, Object> params;

        TypeAnnotation(JClass annotation, Map<String, Object> params) {
            this.annotation = annotation;
            this.params = params;
        }

        @Override
        public void generate(JFormatter f) {
            f.p('@').g(annotation);
            if (params.isEmpty()) return;
            f.p('(');
            boolean first = true;
            for (Map.Entry<String, Object> e : params.entrySet()) {
                if (!first) f.p(',');
                first = false;
                f.p(e.getKey()).p('=').g(literal(e.getValue()));
            }
            f.p(')');
        }

        private static JExpression literal(Object v) {
            return switch (v) {
                case Integer i -> JExpr.lit(i.intValue());
                case Long l -> JExpr.lit(l.longValue());
                case Boolean b -> JExpr.lit(b.booleanValue());
                case String s -> JExpr.lit(s);
                default -> throw new IllegalStateException("Paramètre non supporté : " + v);
            };
        }
    }
}

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


package com.example.xjcval;

import com.example.xjcval.AnnotationRules.Result;
import com.example.xjcval.AnnotationRules.Spec;
import com.sun.codemodel.*;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.Plugin;
import com.sun.tools.xjc.model.CAttributePropertyInfo;
import com.sun.tools.xjc.model.CElementPropertyInfo;
import com.sun.tools.xjc.model.CPropertyInfo;
import com.sun.tools.xjc.model.CValuePropertyInfo;
import com.sun.tools.xjc.outline.ClassOutline;
import com.sun.tools.xjc.outline.Outline;
import com.sun.xml.xsom.*;
import jakarta.validation.constraints.AssertTrue;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Plugin XJC : pose des annotations Jakarta Validation à partir des facettes et occurrences du XSD.
 * Activation : -Xvalidation   (option : -Xvalidation-jsonignore)
 */
public class ValidationPlugin extends Plugin {
    private static final String XS = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    private static final Set<String> LIST_TYPES = Set.of("java.util.List", "java.util.Collection", "java.util.Set");
    private boolean jsonIgnore;

    @Override public String getOptionName() { return "Xvalidation"; }

    @Override
    public String getUsage() {
        return "  -Xvalidation            : annotations Jakarta Validation déduites des contraintes XSD\n"
             + "  -Xvalidation-jsonignore : ajoute @JsonIgnore sur les méthodes @AssertTrue générées (xs:choice)\n";
    }

    @Override
    public int parseArgument(Options opt, String[] args, int i) {
        if ("-Xvalidation-jsonignore".equals(args[i])) { jsonIgnore = true; return 1; }
        return 0;
    }

    /** Retrouve le champ généré d'une particule ou d'une déclaration d'élément. */
    private record Lookup(Map<XSParticle, JFieldVar> byParticle, Map<XSElementDecl, JFieldVar> byDecl) {
        JFieldVar find(XSParticle p) {
            JFieldVar f = byParticle.get(p);
            if (f == null && p.getTerm().isElementDecl()) f = byDecl.get(p.getTerm().asElementDecl());
            return f;
        }
        boolean has(XSParticle p) { return byParticle.containsKey(p); }
    }

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler eh) throws SAXException {
        JCodeModel cm = outline.getCodeModel();
        for (ClassOutline co : outline.getClasses()) {
            XSParticle root = rootParticle(co);
            Map<XSParticle, int[]> occ = new IdentityHashMap<>();
            if (root != null) computeOccurrences(root, 1, 1, occ);

            Lookup lookup = new Lookup(new IdentityHashMap<>(), new IdentityHashMap<>());
            for (CPropertyInfo prop : co.target.getProperties()) {
                JFieldVar field = co.implClass.fields().get(prop.getName(false));
                if (field == null) field = co.implClass.fields().get("_" + prop.getName(false));
                if (field == null) continue;

                XSComponent sc = prop.getSchemaComponent();
                if (sc instanceof XSParticle p) {
                    lookup.byParticle().put(p, field);
                    if (p.getTerm().isElementDecl()) lookup.byDecl().putIfAbsent(p.getTerm().asElementDecl(), field);
                } else if (sc instanceof XSElementDecl d) {
                    lookup.byDecl().putIfAbsent(d, field);
                }
                annotateField(co, prop, field, occ, cm, eh);
            }
            if (root != null) visitChoices(co, root, occ, lookup, cm, new int[]{0}, eh);
        }
        return true;
    }

    // ------------------------------------------------------------------ occurrences

    private static XSParticle rootParticle(ClassOutline co) {
        XSComponent sc = co.target.getSchemaComponent();
        XSComplexType ct = null;
        if (sc instanceof XSComplexType c) ct = c;
        else if (sc instanceof XSElementDecl d && d.getType().isComplexType()) ct = d.getType().asComplexType();
        if (ct == null) return null;
        XSContentType content = ct.getDerivationMethod() == XSType.EXTENSION ? ct.getExplicitContent() : ct.getContentType();
        return content == null ? null : content.asParticle();
    }

    /** Occurrences effectives (min, max) de chaque particule ; sous un choice, min = 0. max : -1 = unbounded. */
    private static void computeOccurrences(XSParticle p, int parentMin, int parentMax, Map<XSParticle, int[]> occ) {
        int min = parentMin * p.getMinOccurs().intValue();
        int max = mulMax(parentMax, maxOf(p));
        occ.put(p, new int[]{min, max});
        XSModelGroup g = groupOf(p);
        if (g == null) return;
        boolean choice = g.getCompositor() == XSModelGroup.CHOICE;
        for (XSParticle k : g.getChildren()) computeOccurrences(k, choice ? 0 : min, max, occ);
    }

    private static int maxOf(XSParticle p) { return p.getMaxOccurs().signum() < 0 ? -1 : p.getMaxOccurs().intValue(); }

    private static int mulMax(int a, int b) {
        if (a == 0 || b == 0) return 0;
        if (a < 0 || b < 0) return -1;
        return a * b;
    }

    private static XSModelGroup groupOf(XSParticle p) {
        XSTerm t = p.getTerm();
        return t.isModelGroupDecl() ? t.asModelGroupDecl().getModelGroup() : t.isModelGroup() ? t.asModelGroup() : null;
    }

    // ------------------------------------------------------------------ champs

    private void annotateField(ClassOutline co, CPropertyInfo prop, JFieldVar field, Map<XSParticle, int[]> occ,
                               JCodeModel cm, ErrorHandler eh) throws SAXException {
        XSComponent sc = prop.getSchemaComponent();
        int min = 0, max = 1;
        XSType type = null;
        boolean complex = false;
        XSSimpleType simple = null;

        if (sc instanceof XSParticle p) {
            int[] o = occ.getOrDefault(p, new int[]{p.getMinOccurs().intValue(), maxOf(p)});
            min = o[0];
            max = o[1];
            if (p.getTerm().isElementDecl()) type = p.getTerm().asElementDecl().getType();
            else complex = true;                       // propriété issue d'un groupe (choice répété) : cascade
        } else if (sc instanceof XSElementDecl d) {
            type = d.getType();
        } else if (sc instanceof XSAttributeUse u) {
            simple = u.getDecl().getType();
        } else if (sc instanceof XSComplexType ct && ct.getContentType().asSimpleType() != null) {
            simple = ct.getContentType().asSimpleType();
        } else if (sc instanceof XSSimpleType st) {
            simple = st;
        }
        if (type != null) {
            if (type.isSimpleType()) simple = type.asSimpleType();
            else complex = !XS.equals(type.getTargetNamespace());
        }

        boolean repeated = prop.isCollection();
        boolean required = repeated ? min >= 1
                : prop instanceof CElementPropertyInfo e ? e.isRequired()
                : prop instanceof CAttributePropertyInfo a ? a.isRequired()
                : prop instanceof CValuePropertyInfo;

        String where = co.implClass.name() + "." + field.name();
        Facets facets = null;
        if (simple != null) {
            facets = FacetResolver.resolve(simple);
            for (String issue : facets.issues()) warn(eh, where + ": " + issue);
        }

        JType jt = field.type();
        boolean javaList = isList(jt);
        JClass elemType = null;
        String javaType = jt.name();
        if (javaList && !((JClass) jt).getTypeParameters().isEmpty()) {
            elemType = ((JClass) jt).getTypeParameters().get(0);
            javaType = elemType.name();
        }

        Result r = AnnotationRules.compute(new AnnotationRules.Input(
                javaList, repeated, required, min, max, complex, facets, javaType, jt.isPrimitive()),
                msg -> warnQuiet(eh, where + ": " + msg));

        for (Spec s : r.onField()) apply(field, s);

        if (!r.onElement().isEmpty() && elemType != null) {
            // List<String>  ->  List<@Size(max = 20) String>
            List<JGenerable> anns = r.onElement().stream()
                    .map(s -> (JGenerable) new AnnotatedClass.TypeAnnotation(cm.ref(s.type()), s.params()))
                    .collect(Collectors.toList());
            field.type(((JClass) jt).erasure().narrow(new AnnotatedClass(elemType, anns)));
        }
    }

    private static boolean isList(JType t) {
        return t instanceof JClass c && LIST_TYPES.contains(c.erasure().fullName());
    }

    private static void apply(JFieldVar field, Spec s) {
        for (JAnnotationUse a : field.annotations()) {
            if (a.getAnnotationClass().fullName().equals(s.type().getName())) return;
        }
        JAnnotationUse use = field.annotate(s.type());
        s.params().forEach((k, v) -> {
            switch (v) {
                case Integer i -> use.param(k, i.intValue());
                case Long l -> use.param(k, l.longValue());
                case Boolean b -> use.param(k, b.booleanValue());
                case String str -> use.param(k, str);
                default -> throw new IllegalStateException("Paramètre non supporté : " + k);
            }
        });
    }

    // ------------------------------------------------------------------ xs:choice

    private record Branch(List<JFieldVar> fields, List<JFieldVar> required) {}

    private void visitChoices(ClassOutline co, XSParticle p, Map<XSParticle, int[]> occ, Lookup lk,
                              JCodeModel cm, int[] counter, ErrorHandler eh) throws SAXException {
        XSModelGroup g = groupOf(p);
        if (g == null || lk.has(p)) return;            // groupe porté par une seule propriété : géré dans annotateField

        XSParticle[] kids = g.getChildren();
        if (g.getCompositor() == XSModelGroup.CHOICE && kids.length > 1) {
            int[] o = occ.get(p);
            if (o[1] == 1) buildChoice(co, kids, o[0] >= 1, occ, lk, cm, ++counter[0], eh);
            else warn(eh, co.implClass.name() + ": xs:choice répété non porté par une liste, non couvert");
        }
        for (XSParticle k : kids) visitChoices(co, k, occ, lk, cm, counter, eh);
    }

    private void buildChoice(ClassOutline co, XSParticle[] kids, boolean required, Map<XSParticle, int[]> occ,
                             Lookup lk, JCodeModel cm, int n, ErrorHandler eh) throws SAXException {
        List<Branch> branches = new ArrayList<>();
        for (XSParticle k : kids) {
            List<JFieldVar> fs = new ArrayList<>(), req = new ArrayList<>();
            boolean ok = collect(k, 1, lk, fs, req)
                    && !fs.isEmpty() && fs.stream().noneMatch(f -> f.type().isPrimitive());
            if (!ok) {
                warn(eh, co.implClass.name() + ": xs:choice non traduit (branche non mappée, wildcard ou champ primitif)");
                return;
            }
            branches.add(new Branch(fs, req));
        }

        JMethod m = co.implClass.method(JMod.PUBLIC, cm.BOOLEAN, "isChoice" + n + "Valid");
        List<String> labels = branches.stream().map(b -> b.fields().size() == 1
                ? b.fields().get(0).name()
                : "(" + b.fields().stream().map(JFieldVar::name).collect(Collectors.joining(" + ")) + ")").toList();
        m.annotate(AssertTrue.class).param("message",
                (required ? "Exactly one" : "At most one") + " of " + labels + " must be set");
        m.annotate(cm.ref("jakarta.xml.bind.annotation.XmlTransient"));
        if (jsonIgnore) m.annotate(cm.ref("com.fasterxml.jackson.annotation.JsonIgnore"));

        JExpression count = null;
        List<JExpression> branchRules = new ArrayList<>();
        for (Branch b : branches) {
            JExpression any = fold(b.fields(), true);
            JExpression term = JOp.cond(any, JExpr.lit(1), JExpr.lit(0));
            count = count == null ? term : count.plus(term);
            // branche choisie => tous ses champs obligatoires doivent être renseignés
            if (b.fields().size() > 1 && !b.required().isEmpty()) {
                branchRules.add(any.not().cor(fold(b.required(), false)));
            }
        }
        JExpression cond = required ? count.eq(JExpr.lit(1)) : count.lte(JExpr.lit(1));
        for (JExpression r : branchRules) cond = cond.cand(r);
        m.body()._return(cond);
    }

    /** Champs d'une branche ; req = champs obligatoires *dans* la branche (localMin >= 1). */
    private boolean collect(XSParticle k, int localMin, Lookup lk, List<JFieldVar> fs, List<JFieldVar> req) {
        int lm = localMin * k.getMinOccurs().intValue();
        JFieldVar direct = lk.find(k);
        if (direct != null) {
            fs.add(direct);
            if (lm >= 1) req.add(direct);
            return true;
        }
        XSModelGroup g = groupOf(k);
        if (g == null) return false;                   // élément non mappé ou wildcard
        boolean choice = g.getCompositor() == XSModelGroup.CHOICE;
        for (XSParticle c : g.getChildren()) {
            if (!collect(c, choice ? 0 : lm, lk, fs, req)) return false;
        }
        return true;
    }

    /** OU (or=true) ou ET (or=false) des tests « champ renseigné ». */
    private static JExpression fold(List<JFieldVar> fields, boolean or) {
        JExpression acc = null;
        for (JFieldVar f : fields) {
            JExpression ref = JExpr._this().ref(f);
            JExpression present = ref.ne(JExpr._null());
            if (isList(f.type())) present = present.cand(ref.invoke("isEmpty").not());
            acc = acc == null ? present : (or ? acc.cor(present) : acc.cand(present));
        }
        return acc;
    }

    // ------------------------------------------------------------------ logs

    private static void warn(ErrorHandler eh, String msg) throws SAXException {
        eh.warning(new SAXParseException("[Xvalidation] " + msg, null));
    }

    private static void warnQuiet(ErrorHandler eh, String msg) {
        try { warn(eh, msg); } catch (SAXException ignored) { }
    }
}



    @Test void facetsRegexAndsStepsTogether() {
        Facets f = new Facets(null, null, null, null, null, null, null, null, null,
                java.util.List.of("[a-z]+", ".{3}"), java.util.List.of(), java.util.List.of(), null);
        assertThat(Pattern.matches(f.regex(), "abc")).isTrue();
        assertThat(Pattern.matches(f.regex(), "abcd")).isFalse();
    }



    @Test void listElementFacetsAreEnforced() {
        Order o = validOrder();
        o.getTags().add("x".repeat(21));                         // maxLength 20 sur chaque tag
        assertThat(paths(o)).anyMatch(p -> p.startsWith("tags[0]"));
    }







<xs:element name="notes" minOccurs="0">          <!-- xs:list : 5 mots max, 10 caractères par mot -->
  <xs:simpleType>
    <xs:restriction>
      <xs:simpleType>
        <xs:list>
          <xs:simpleType><xs:restriction base="xs:string"><xs:maxLength value="10"/></xs:restriction></xs:simpleType>
        </xs:list>
      </xs:simpleType>
      <xs:maxLength value="5"/>
    </xs:restriction>
  </xs:simpleType>
</xs:element>

<xs:choice minOccurs="0">                         <!-- choice avec branche séquence -->
  <xs:element name="pickup" type="xs:string"/>
  <xs:sequence>
    <xs:element name="carrier" type="xs:string"/>
    <xs:element name="trackingNo" type="xs:string"/>
  </xs:sequence>
</xs:choice>






    @Test void xsListFacets() {
        Order o = validOrder();
        for (int i = 0; i < 6; i++) o.getNotes().add("mot");                 // > 5 items
        o.getNotes().set(0, "x".repeat(11));                                 // > 10 caractères
        assertThat(paths(o)).contains("notes").anyMatch(p -> p.startsWith("notes[0]"));
    }

    @Test void choiceWithSequenceBranch() {
        Order o = validOrder();
        o.setCarrier("DHL");                                                 // trackingNo manquant
        assertThat(paths(o)).contains("choice2Valid");
        o.setTrackingNo("123");
        assertThat(paths(o)).doesNotContain("choice2Valid");
        o.setPickup("magasin");                                              // deux branches à la fois
        assertThat(paths(o)).contains("choice2Valid");
    }


  

 


  
  

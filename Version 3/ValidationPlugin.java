package com.example.xjcval;

import com.example.xjcval.AnnotationRules.Result;
import com.example.xjcval.AnnotationRules.Spec;
import com.sun.codemodel.*;
import com.sun.tools.xjc.BadCommandLineException;
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
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Plugin XJC : pose des annotations Jakarta Validation à partir des facettes et occurrences du XSD,
 * puis celles déclarées dans un fichier de règles externe.
 *
 * Options : -Xvalidation   -Xvalidation-jsonignore   -Xvalidation-rules <fichier.xml>
 */
public class ValidationPlugin extends Plugin {
    private static final String XS = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    private static final Set<String> LIST_TYPES = Set.of("java.util.List", "java.util.Collection", "java.util.Set");

    private boolean jsonIgnore;
    private File rulesFile;

    @Override public String getOptionName() { return "Xvalidation"; }

    @Override
    public String getUsage() {
        return "  -Xvalidation            : annotations Jakarta Validation déduites des contraintes XSD\n"
             + "  -Xvalidation-jsonignore : ajoute @JsonIgnore sur les méthodes @AssertTrue générées (xs:choice)\n"
             + "  -Xvalidation-rules <f>  : fichier XML de règles supplémentaires (hors XSD)\n";
    }

    @Override
    public int parseArgument(Options opt, String[] args, int i) throws BadCommandLineException, IOException {
        if ("-Xvalidation-jsonignore".equals(args[i])) { jsonIgnore = true; return 1; }
        if ("-Xvalidation-rules".equals(args[i])) {
            rulesFile = new File(opt.requireArgument("-Xvalidation-rules", args, ++i));
            return 2;
        }
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

    /** Appelé par XJC une fois le modèle Java construit, avant l'écriture des fichiers. */
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
        return rulesFile == null || applyCustomRules(outline, eh);
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
        setParams(field.annotate(s.type()), s.params());
    }

    private static void setParams(JAnnotationUse use, Map<String, Object> params) {
        params.forEach((k, v) -> {
            switch (v) {
                case Integer i -> use.param(k, i.intValue());
                case Long l -> use.param(k, l.longValue());
                case Boolean b -> use.param(k, b.booleanValue());
                case String s -> use.param(k, s);
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
            if (o[1] == 1) buildChoice(co, kids, o[0] >= 1, lk, cm, ++counter[0], eh);
            else warn(eh, co.implClass.name() + ": xs:choice répété non porté par une liste, non couvert");
        }
        for (XSParticle k : kids) visitChoices(co, k, occ, lk, cm, counter, eh);
    }

    /** Génère : @AssertTrue public boolean isChoiceNValid() { ... } */
    private void buildChoice(ClassOutline co, XSParticle[] kids, boolean required, Lookup lk,
                             JCodeModel cm, int n, ErrorHandler eh) throws SAXException {
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

    // ------------------------------------------------------------------ règles du fichier validation-rules.xml

    /** Pose les annotations déclarées dans le fichier de règles. Une faute de frappe fait échouer la génération. */
    private boolean applyCustomRules(Outline outline, ErrorHandler eh) throws SAXException {
        List<RulesFile.Rule> rules;
        try {
            rules = RulesFile.load(rulesFile);
        } catch (Exception e) {
            throw new SAXException("Lecture impossible de " + rulesFile + " : " + e.getMessage(), e);
        }
        JCodeModel cm = outline.getCodeModel();
        boolean ok = true;

        for (RulesFile.Rule r : rules) {
            ClassOutline co = findClass(outline, r.className());
            if (co == null) {
                eh.error(new SAXParseException("[Xvalidation] règle sur une classe inconnue : " + r.className(), null));
                ok = false;
                continue;
            }
            JAnnotatable target = co.implClass;
            if (r.fieldName() != null) {
                JFieldVar field = co.implClass.fields().get(r.fieldName());
                if (field == null) {
                    eh.error(new SAXParseException("[Xvalidation] champ inconnu : " + r.className() + "." + r.fieldName(), null));
                    ok = false;
                    continue;
                }
                target = field;
            }
            JClass annotation = cm.ref(r.annotation());        // aucune dépendance du plugin vers vos règles
            boolean already = target.annotations().stream()
                    .anyMatch(a -> a.getAnnotationClass().fullName().equals(annotation.fullName()));
            if (!already) setParams(target.annotate(annotation), r.params());
        }
        return ok;
    }

    private static ClassOutline findClass(Outline outline, String name) {
        for (ClassOutline co : outline.getClasses()) {
            if (co.implClass.name().equals(name) || co.implClass.fullName().equals(name)) return co;
        }
        return null;
    }

    // ------------------------------------------------------------------ logs

    private static void warn(ErrorHandler eh, String msg) throws SAXException {
        eh.warning(new SAXParseException("[Xvalidation] " + msg, null));
    }

    private static void warnQuiet(ErrorHandler eh, String msg) {
        try { warn(eh, msg); } catch (SAXException ignored) { }
    }
}

package com.example.xjcval;

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
import java.math.BigInteger;
import java.util.*;

/**
 * Plugin XJC : lit les facettes / occurrences du XSD pendant la génération et pose directement
 * les annotations Jakarta Validation sur les champs générés.
 * Activation : -Xvalidation  (option : -Xvalidation-jsonignore)
 */
public class ValidationPlugin extends Plugin {
    private static final String XS = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    private boolean jsonIgnore;

    @Override
    public String getOptionName() { return "Xvalidation"; }

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

    /** Appelé par XJC une fois le modèle Java construit, avant l'écriture des fichiers. */
    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) throws SAXException {
        JCodeModel cm = outline.getCodeModel();
        for (ClassOutline co : outline.getClasses()) {
            Map<XSParticle, JFieldVar> byParticle = new IdentityHashMap<>();
            for (CPropertyInfo prop : co.target.getProperties()) {
                JFieldVar field = co.implClass.fields().get(prop.getName(false));
                if (field == null) field = co.implClass.fields().get("_" + prop.getName(false));
                if (field == null) continue;
                annotateField(co, prop, field, errorHandler);
                if (prop.getSchemaComponent() instanceof XSParticle p) byParticle.put(p, field);
            }
            addChoiceRules(co, byParticle, cm, errorHandler);
        }
        return true;
    }

    // ---------- champs ----------

    private void annotateField(ClassOutline co, CPropertyInfo prop, JFieldVar field, ErrorHandler eh) throws SAXException {
        XSComponent sc = prop.getSchemaComponent();
        int min = 0, max = 1;
        XSSimpleType simple = null;
        boolean complex = false;

        if (sc instanceof XSParticle p && p.getTerm().isElementDecl()) {
            XSType t = p.getTerm().asElementDecl().getType();
            if (t.isSimpleType()) simple = t.asSimpleType();
            else complex = !XS.equals(t.getTargetNamespace());
            min = p.getMinOccurs().intValue();
            max = p.getMaxOccurs().signum() < 0 ? -1 : p.getMaxOccurs().intValue();
        } else if (sc instanceof XSAttributeUse u) {
            simple = u.getDecl().getType();
        } else if (sc instanceof XSComplexType ct && ct.getContentType().asSimpleType() != null) {
            simple = ct.getContentType().asSimpleType();
        } else if (sc instanceof XSSimpleType st) {
            simple = st;
        }

        boolean required = prop instanceof CElementPropertyInfo e ? e.isRequired()
                : prop instanceof CAttributePropertyInfo a ? a.isRequired()
                : prop instanceof CValuePropertyInfo;

        Facets facets = null;
        String where = co.implClass.name() + "." + field.name();
        if (simple != null) {
            facets = FacetResolver.resolve(simple);
            for (String issue : facets.issues()) warn(eh, where + ": " + issue);
        }

        JType type = field.type();
        boolean collection = prop.isCollection();
        String javaType = collection && type instanceof JClass jc && !jc.getTypeParameters().isEmpty()
                ? jc.getTypeParameters().get(0).name() : type.name();

        List<Spec> specs = AnnotationRules.compute(new AnnotationRules.Input(
                collection, required, min, max, complex, facets, javaType, type.isPrimitive()),
                msg -> warnQuiet(eh, where + ": " + msg));
        for (Spec s : specs) apply(field, s);
    }

    private static void apply(JFieldVar field, Spec s) {
        for (JAnnotationUse a : field.annotations()) {               // idempotence / annotations existantes
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

    // ---------- xs:choice ----------

    private void addChoiceRules(ClassOutline co, Map<XSParticle, JFieldVar> byParticle, JCodeModel cm,
                                ErrorHandler eh) throws SAXException {
        XSComponent sc = co.target.getSchemaComponent();
        XSComplexType ct = null;
        if (sc instanceof XSComplexType c) ct = c;
        else if (sc instanceof XSElementDecl d && d.getType().isComplexType()) ct = d.getType().asComplexType();
        if (ct == null) return;

        XSContentType content = ct.getDerivationMethod() == XSType.EXTENSION ? ct.getExplicitContent() : ct.getContentType();
        if (content == null || content.asParticle() == null) return;
        walk(co, content.asParticle(), 1, byParticle, cm, new int[]{0}, eh);
    }

    private void walk(ClassOutline co, XSParticle p, int parentMin, Map<XSParticle, JFieldVar> byParticle,
                      JCodeModel cm, int[] counter, ErrorHandler eh) throws SAXException {
        XSTerm t = p.getTerm();
        XSModelGroup g = t.isModelGroupDecl() ? t.asModelGroupDecl().getModelGroup()
                       : t.isModelGroup() ? t.asModelGroup() : null;
        if (g == null) return;

        int min = parentMin * p.getMinOccurs().intValue();
        boolean choice = g.getCompositor() == XSModelGroup.CHOICE;
        XSParticle[] kids = g.getChildren();

        if (choice && kids.length > 1 && p.getMaxOccurs().equals(BigInteger.ONE)) {
            List<JFieldVar> fields = new ArrayList<>();
            for (XSParticle k : kids) fields.add(byParticle.get(k));
            boolean ok = fields.stream().noneMatch(f -> f == null || f.type().isPrimitive()
                    || f.type().erasure().fullName().equals("java.util.List"));
            if (ok) addChoiceMethod(co, fields, min >= 1, cm, ++counter[0]);
            else warn(eh, co.implClass.name() + ": xs:choice non traduit (champ répété, primitif ou introuvable)");
        }
        for (XSParticle k : kids) walk(co, k, choice ? 0 : min, byParticle, cm, counter, eh);
    }

    /** Génère : @AssertTrue public boolean isChoiceNValid() { return (a != null ? 1 : 0) + ... == 1; } */
    private void addChoiceMethod(ClassOutline co, List<JFieldVar> fields, boolean required, JCodeModel cm, int n) {
        JMethod m = co.implClass.method(JMod.PUBLIC, cm.BOOLEAN, "isChoice" + n + "Valid");
        List<String> names = fields.stream().map(JFieldVar::name).toList();
        m.annotate(AssertTrue.class).param("message",
                (required ? "Exactly one" : "At most one") + " of " + names + " must be set");
        m.annotate(cm.ref("jakarta.xml.bind.annotation.XmlTransient"));
        if (jsonIgnore) m.annotate(cm.ref("com.fasterxml.jackson.annotation.JsonIgnore"));

        JExpression count = null;
        for (JFieldVar f : fields) {
            JExpression term = JOp.cond(JExpr._this().ref(f).ne(JExpr._null()), JExpr.lit(1), JExpr.lit(0));
            count = count == null ? term : count.plus(term);
        }
        m.body()._return(required ? count.eq(JExpr.lit(1)) : count.lte(JExpr.lit(1)));
    }

    private static void warn(ErrorHandler eh, String msg) throws SAXException {
        eh.warning(new SAXParseException("[Xvalidation] " + msg, null));
    }

    /** Variante pour les lambdas (pas d'exception vérifiée). */
    private static void warnQuiet(ErrorHandler eh, String msg) {
        try { warn(eh, msg); } catch (SAXException ignored) { }
    }
}

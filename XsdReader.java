package com.example.xsdval.xsd;

import com.example.xsdval.Report;
import com.example.xsdval.model.XsdModel.*;
import com.sun.xml.xsom.*;
import com.sun.xml.xsom.parser.XSOMParser;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/** Parcourt le XSD avec XSOM et produit un modèle de contraintes indexé par type. */
public final class XsdReader {
    private static final String XS = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    /** Types prédéfinis dont les bornes ont une vraie valeur de validation. */
    private static final Set<String> NUMERIC_BUILTINS = Set.of(
            "positiveInteger", "nonNegativeInteger", "negativeInteger", "nonPositiveInteger",
            "unsignedByte", "unsignedShort", "unsignedInt", "unsignedLong");

    private final Report report;
    private final Map<String, ClassInfo> classes = new LinkedHashMap<>();

    public XsdReader(Report report) { this.report = report; }

    public Map<String, ClassInfo> read(List<File> xsds) throws Exception {
        SAXParserFactory spf = SAXParserFactory.newInstance();
        spf.setNamespaceAware(true);
        XSOMParser parser = new XSOMParser(spf);
        parser.setErrorHandler(new ErrorHandler() {
            public void warning(SAXParseException e) { report.warn("XSD: " + e.getMessage()); }
            public void error(SAXParseException e) throws SAXException { throw e; }
            public void fatalError(SAXParseException e) throws SAXException { throw e; }
        });
        for (File f : xsds) parser.parse(f);
        XSSchemaSet set = parser.getResult();

        for (Iterator<XSSchema> it = set.iterateSchema(); it.hasNext(); ) {
            XSSchema schema = it.next();
            if (XS.equals(schema.getTargetNamespace())) continue;
            for (XSComplexType ct : schema.getComplexTypes().values()) {
                complex("type:" + ct.getName(), ct);
            }
            for (XSElementDecl e : schema.getElementDecls().values()) {
                identity("element:" + e.getName(), e);
                if (e.getType().isComplexType() && e.getType().isLocal()) {
                    complex("element:" + e.getName(), e.getType().asComplexType());
                }
            }
        }
        return classes;
    }

    private void complex(String key, XSComplexType ct) {
        if (classes.containsKey(key)) return;
        ClassInfo ci = new ClassInfo(key);
        classes.put(key, ci);

        boolean ext = ct.getDerivationMethod() == XSType.EXTENSION;

        XSSimpleType simple = ct.getContentType().asSimpleType();
        if (simple != null) {                                   // simpleContent -> champ "value"
            ci.fields().put("#value", new FieldInfo("#value", 1, 1, resolve(key, "value", simple), null));
        } else {
            XSContentType content = ext ? ct.getExplicitContent() : ct.getContentType();
            if (content != null && content.asParticle() != null) {
                walk(key, ci, content.asParticle(), 1, 1);
            }
        }

        Collection<? extends XSAttributeUse> uses = ext ? ct.getDeclaredAttributeUses() : ct.getAttributeUses();
        for (XSAttributeUse u : uses) {
            XSAttributeDecl d = u.getDecl();
            String k = "@" + d.getName();
            ci.fields().put(k, new FieldInfo(k, u.isRequired() ? 1 : 0, 1,
                    resolve(key, k, d.getType()), null));
        }
    }

    /** min/max = occurrences cumulées des particules parentes. */
    private void walk(String key, ClassInfo ci, XSParticle p, int parentMin, int parentMax) {
        int min = parentMin * occ(p.getMinOccurs());
        int max = mulMax(parentMax, occ(p.getMaxOccurs()));
        XSTerm term = p.getTerm();

        if (term.isElementDecl()) {
            element(key, ci, term.asElementDecl(), min, max);
            return;
        }
        XSModelGroup g = term.isModelGroupDecl() ? term.asModelGroupDecl().getModelGroup()
                       : term.isModelGroup() ? term.asModelGroup() : null;
        if (g == null) return;                                   // xs:any : ignoré

        boolean choice = g.getCompositor() == XSModelGroup.CHOICE;
        List<String> names = new ArrayList<>();
        boolean allElements = true;
        for (XSParticle c : g.getChildren()) {
            walk(key, ci, c, choice ? 0 : min, max);
            if (c.getTerm().isElementDecl()) names.add(c.getTerm().asElementDecl().getName());
            else allElements = false;
        }
        if (choice && g.getChildren().length > 1) {
            if (max == 1 && allElements) ci.choices().add(new ChoiceInfo(names, min >= 1));
            else report.warn(key + ": xs:choice répété ou imbriqué, non couvert");
        }
    }

    private void element(String key, ClassInfo ci, XSElementDecl d, int min, int max) {
        String name = d.getName();
        identity(key, d);
        XSType t = d.getType();
        Facets facets = null;
        String complexKey = null;
        if (t.isSimpleType()) {
            facets = resolve(key, name, t.asSimpleType());
        } else if (!XS.equals(t.getTargetNamespace())) {
            XSComplexType ct = t.asComplexType();
            complexKey = ct.isLocal() ? key + "/" + name : "type:" + ct.getName();
            complex(complexKey, ct);
        }
        ci.fields().put(name, new FieldInfo(name, min, max, facets, complexKey));
    }

    private void identity(String key, XSElementDecl d) {
        for (XSIdentityConstraint ic : d.getIdentityConstraints()) {
            report.warn(key + ": contrainte d'identité '" + ic.getName()
                    + "' (unique/key/keyref) non traduisible en annotation de champ");
        }
    }

    private Facets resolve(String key, String field, XSSimpleType st) {
        Facets f = resolve(st);
        f.issues().forEach(i -> report.warn(key + "/" + field + ": " + i));
        return f;
    }

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
                    single.putIfAbsent(n, v);              // la restriction la plus dérivée gagne
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

    private static int occ(BigInteger b) { return b.signum() < 0 ? -1 : b.intValue(); }

    private static int mulMax(int a, int b) {
        if (a == 0 || b == 0) return 0;
        if (a < 0 || b < 0) return -1;
        return a * b;
    }
}

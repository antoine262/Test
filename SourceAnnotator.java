package com.example.xsdval.java;

import com.example.xsdval.Report;
import com.example.xsdval.model.XsdModel.*;
import com.example.xsdval.rules.Ann;
import com.example.xsdval.rules.AnnotationRules;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Ajoute les annotations dans les sources Java. Idempotent : une annotation déjà présente
 * (même nom) n'est jamais ajoutée ni modifiée.
 * Le rapprochement XSD -> Java s'appuie sur les annotations JAXB déjà présentes
 * (@XmlType, @XmlRootElement, @XmlElement(name), @XmlAttribute(name), @XmlValue),
 * donc sans réimplémenter les règles de nommage de JAXB.
 */
public final class SourceAnnotator {
    private static final Set<String> LISTS = Set.of("List", "Collection", "Set");
    private record FieldRef(String name, boolean list, boolean primitive) {}

    private final Map<String, ClassInfo> model;
    private final Report report;

    public SourceAnnotator(Map<String, ClassInfo> model, Report report) {
        this.model = model;
        this.report = report;
        StaticJavaParser.setConfiguration(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
                .setLexicalPreservationEnabled(true));
    }

    /** @return true si le fichier a été modifié */
    public boolean annotate(Path file) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(file);
        boolean changed = false;
        for (TypeDeclaration<?> td : cu.getTypes()) {
            if (td instanceof ClassOrInterfaceDeclaration c && !c.isInterface()) {
                changed |= process(cu, c, null, null);
            }
        }
        if (changed) Files.writeString(file, LexicalPreservingPrinter.print(cu));
        return changed;
    }

    private boolean process(CompilationUnit cu, ClassOrInterfaceDeclaration c,
                            ClassOrInterfaceDeclaration parent, String parentKey) {
        boolean changed = false;
        String key = keyOf(c, parent, parentKey);
        ClassInfo ci = key == null ? null : model.get(key);
        if (ci != null) changed |= annotateClass(cu, c, ci);
        for (BodyDeclaration<?> m : c.getMembers()) {
            if (m instanceof ClassOrInterfaceDeclaration n && !n.isInterface()) {
                changed |= process(cu, n, c, key);
            }
        }
        return changed;
    }

    private boolean annotateClass(CompilationUnit cu, ClassOrInterfaceDeclaration c, ClassInfo ci) {
        boolean changed = false;
        Map<String, FieldRef> refs = new HashMap<>();
        Set<String> seen = new HashSet<>();

        for (FieldDeclaration fd : c.getFields()) {
            if (fd.isStatic()) continue;
            for (VariableDeclarator v : fd.getVariables()) {
                String k = xsdKey(fd, v);
                FieldInfo fi = k == null ? null : ci.fields().get(k);
                if (fi == null) continue;
                seen.add(k);

                Type t = v.getType();
                Optional<Type> arg = listArg(t);
                boolean list = arg.isPresent();
                String javaType = simpleName(list ? arg.get() : t);
                refs.put(k, new FieldRef(v.getNameAsString(), list, t.isPrimitiveType()));

                String where = ci.key() + "#" + v.getNameAsString();
                var r = AnnotationRules.compute(fi, javaType, list, t.isPrimitiveType(), report, where);

                for (Ann a : r.onField()) {
                    if (fd.getAnnotationByName(a.name()).isEmpty()) {
                        fd.addAnnotation(StaticJavaParser.parseAnnotation(a.source()));
                        cu.addImport(a.fqn());
                        report.added(where + " " + a.source());
                        changed = true;
                    }
                }
                if (list) {
                    Type ta = arg.get();
                    for (Ann a : r.onElement()) {
                        if (ta.getAnnotationByName(a.name()).isEmpty()) {
                            ta.addAnnotation(StaticJavaParser.parseAnnotation(a.source()));
                            cu.addImport(a.fqn());
                            report.added(where + " <" + a.source() + ">");
                            changed = true;
                        }
                    }
                }
            }
        }

        for (String k : ci.fields().keySet()) {
            if (!seen.contains(k)) {
                report.warn(ci.key() + ": champ XSD '" + k + "' introuvable dans " + c.getNameAsString()
                        + " (JAXBElement, @XmlElements, héritage ?)");
            }
        }

        // xs:choice -> méthode @AssertTrue (aucune dépendance runtime supplémentaire)
        int n = 0;
        for (ChoiceInfo ch : ci.choices()) {
            n++;
            String method = "isChoice" + n + "Valid";
            if (!c.getMethodsByName(method).isEmpty()) continue;
            List<FieldRef> fs = ch.keys().stream().map(refs::get).toList();
            if (fs.stream().anyMatch(x -> x == null || x.primitive())) {
                report.warn(ci.key() + ": xs:choice " + ch.keys() + " non traduit (champ introuvable ou primitif)");
                continue;
            }
            String count = fs.stream()
                    .map(x -> x.list()
                            ? "(" + x.name() + " != null && !" + x.name() + ".isEmpty() ? 1 : 0)"
                            : "(" + x.name() + " != null ? 1 : 0)")
                    .collect(Collectors.joining(" + "));
            String msg = (ch.required() ? "Exactly one" : "At most one") + " of " + ch.keys() + " must be set";
            String code = """
                    @XmlTransient
                    @AssertTrue(message = %s)
                    public boolean %s() {
                        return (%s) %s;
                    }
                    """.formatted(Ann.litPublic(msg), method, count, ch.required() ? "== 1" : "<= 1");
            c.addMember(StaticJavaParser.parseBodyDeclaration(code));
            cu.addImport("jakarta.validation.constraints.AssertTrue");
            cu.addImport("jakarta.xml.bind.annotation.XmlTransient");
            report.added(ci.key() + " @AssertTrue " + method + "()");
            changed = true;
        }
        return changed;
    }

    // ---------- rapprochement Java <-> XSD ----------

    private String keyOf(ClassOrInterfaceDeclaration c, ClassOrInterfaceDeclaration parent, String parentKey) {
        String t = c.getAnnotationByName("XmlType").map(a -> stringAttr(a, "name")).orElse(null);
        if (t != null && !t.isEmpty() && !t.startsWith("##")) return "type:" + t;

        if (parent != null) {                       // type anonyme imbriqué
            if (parentKey == null) return null;
            for (FieldDeclaration fd : parent.getFields()) {
                for (VariableDeclarator v : fd.getVariables()) {
                    Type vt = v.getType();
                    String tn = simpleName(listArg(vt).orElse(vt));
                    String k = xsdKey(fd, v);
                    if (tn.equals(c.getNameAsString()) && k != null && !k.startsWith("@") && !k.startsWith("#")) {
                        return parentKey + "/" + k;
                    }
                }
            }
            return null;
        }
        Optional<AnnotationExpr> root = c.getAnnotationByName("XmlRootElement");
        if (root.isPresent()) {
            String n = stringAttr(root.get(), "name");
            if (n == null || n.startsWith("##")) {
                String cn = c.getNameAsString();
                n = Character.toLowerCase(cn.charAt(0)) + cn.substring(1);
            }
            return "element:" + n;
        }
        return null;
    }

    private static String xsdKey(FieldDeclaration fd, VariableDeclarator v) {
        String name = v.getNameAsString();
        for (String skip : List.of("XmlTransient", "XmlElementRef", "XmlElements", "XmlAnyElement")) {
            if (fd.getAnnotationByName(skip).isPresent()) return null;
        }
        if (fd.getAnnotationByName("XmlValue").isPresent()) return "#value";
        Optional<AnnotationExpr> attr = fd.getAnnotationByName("XmlAttribute");
        if (attr.isPresent()) return "@" + named(stringAttr(attr.get(), "name"), name);
        String n = fd.getAnnotationByName("XmlElement").map(a -> stringAttr(a, "name")).orElse(null);
        return named(n, name.startsWith("_") ? name.substring(1) : name);
    }

    private static String named(String n, String fallback) {
        return n != null && !n.isEmpty() && !n.startsWith("##") ? n : fallback;
    }

    static String stringAttr(AnnotationExpr a, String attr) {
        if (a instanceof NormalAnnotationExpr n) {
            for (MemberValuePair p : n.getPairs()) {
                if (p.getNameAsString().equals(attr) && p.getValue() instanceof StringLiteralExpr s) return s.asString();
            }
        }
        return null;
    }

    private static Optional<Type> listArg(Type t) {
        if (t.isClassOrInterfaceType()) {
            var ct = t.asClassOrInterfaceType();
            if (LISTS.contains(ct.getNameAsString())) {
                return ct.getTypeArguments().filter(a -> a.size() == 1).map(a -> a.get(0));
            }
        }
        return Optional.empty();
    }

    private static String simpleName(Type t) {
        return t.isClassOrInterfaceType() ? t.asClassOrInterfaceType().getNameAsString() : t.asString();
    }
}

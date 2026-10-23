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

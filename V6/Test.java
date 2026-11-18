
/*
========================================================
1. ANNOTATIONS JAVA
========================================================
*/

// CustomProperty.java

package com.example.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Repeatable;

@Retention(RetentionPolicy.RUNTIME)
@Repeatable(CustomProperties.class)
public @interface CustomProperty {

    String key();

    String value();
}


// CustomProperties.java

package com.example.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface CustomProperties {

    CustomProperty[] value();
}


// JsonMeta.java

package com.example.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface JsonMeta {

    String key();

    String value();
}


/*
========================================================
2. MODELE DE METADONNEES
========================================================
*/

// Metadata.java

package com.example.xjc.metadata;

public class Metadata {

    private static final String JSON_META = "jsonMeta";
    private static final String CUSTOM_PROPERTY = "customProperty";

    private static final String KEY = "key";
    private static final String VALUE = "value";

    private final String type;
    private final String key;
    private final String value;

    public Metadata(String type, String key, String value) {
        this.type = type;
        this.key = key;
        this.value = value;
    }

    public String getType() {
        return type;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }
}


/*
========================================================
3. LECTURE DES METADONNEES XSD
========================================================
*/

// MetadataReader.java

package com.example.xjc.metadata;

import com.sun.tools.xjc.model.CPropertyInfo;
import com.sun.xml.xsom.XSAnnotation;
import com.sun.xml.xsom.XSComponent;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;

public class MetadataReader {

    private static final String XSD_NS =
            "http://www.w3.org/2001/XMLSchema";

    private static final String APPINFO = "appinfo";
    private static final String JSON_META = "jsonMeta";
    private static final String CUSTOM_PROPERTY = "customProperty";

    private static final String KEY = "key";
    private static final String VALUE = "value";

    public List<Metadata> read(CPropertyInfo property) {

        List<Metadata> result = new ArrayList<>();

        XSComponent component = property.getSchemaComponent();

        if (component == null) {
            return result;
        }

        XSAnnotation annotation = component.getAnnotation();

        if (annotation == null ||
                !(annotation.getAnnotation() instanceof Element)) {
            return result;
        }

        Element root = (Element) annotation.getAnnotation();

        List<Element> appinfos = findAppinfo(root);

        for (Element appinfo : appinfos) {
            readAppinfo(appinfo, result);
        }

        return result;
    }

    private List<Element> findAppinfo(Element root) {

        List<Element> result = new ArrayList<>();

        if (isElement(root, APPINFO)) {
            result.add(root);
            return result;
        }

        NodeList nodes =
                root.getElementsByTagNameNS(XSD_NS, APPINFO);

        for (int i = 0; i < nodes.getLength(); i++) {
            result.add((Element) nodes.item(i));
        }

        return result;
    }

    private void readAppinfo(
            Element appinfo,
            List<Metadata> result) {

        for (Element element : getChildren(appinfo)) {

            String type = element.getLocalName();

            if (JSON_META.equals(type) ||
                    CUSTOM_PROPERTY.equals(type)) {

                String key = element.getAttribute(KEY);
                String value = element.getAttribute(VALUE);

                if (key.isBlank()) {
                    continue;
                }

                result.add(new Metadata(type, key, value));
            }
        }
    }

    private List<Element> getChildren(Element parent) {

        List<Element> result = new ArrayList<>();

        NodeList nodes = parent.getChildNodes();

        for (int i = 0; i < nodes.getLength(); i++) {

            Node node = nodes.item(i);

            if (node instanceof Element) {
                result.add((Element) node);
            }
        }

        return result;
    }

    private boolean isElement(Element element, String name) {

        return name.equals(element.getLocalName());
    }
}


/*
========================================================
4. CONTRAT DES PROCESSORS
========================================================
*/

// AnnotationProcessor.java

package com.example.xjc.processor;

import com.example.xjc.metadata.Metadata;

import com.sun.codemodel.JFieldVar;
import com.sun.tools.xjc.outline.Outline;

public interface AnnotationProcessor {

    String getType();

    void process(
            JFieldVar field,
            Metadata metadata,
            Outline outline
    );
}


/*
========================================================
5. PROCESSOR JSON META
========================================================
*/

// JsonMetaProcessor.java

package com.example.xjc.processor;

import com.example.xjc.metadata.Metadata;

import com.sun.codemodel.JAnnotationUse;
import com.sun.codemodel.JFieldVar;
import com.sun.tools.xjc.outline.Outline;

public class JsonMetaProcessor implements AnnotationProcessor {

    private static final String TYPE = "jsonMeta";

    private static final String ANNOTATION =
            "com.example.annotations.JsonMeta";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public void process(
            JFieldVar field,
            Metadata metadata,
            Outline outline) {

        JAnnotationUse annotation =
                field.annotate(
                        outline.getCodeModel().ref(ANNOTATION)
                );

        annotation.param("key", metadata.getKey());
        annotation.param("value", metadata.getValue());
    }
}


/*
========================================================
6. PROCESSOR CUSTOM PROPERTY
========================================================
*/

// CustomPropertyProcessor.java

package com.example.xjc.processor;

import com.example.xjc.metadata.Metadata;

import com.sun.codemodel.JAnnotationUse;
import com.sun.codemodel.JFieldVar;
import com.sun.tools.xjc.outline.Outline;

public class CustomPropertyProcessor implements AnnotationProcessor {

    private static final String TYPE = "customProperty";

    private static final String ANNOTATION =
            "com.example.annotations.CustomProperty";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public void process(
            JFieldVar field,
            Metadata metadata,
            Outline outline) {

        JAnnotationUse annotation =
                field.annotate(
                        outline.getCodeModel().ref(ANNOTATION)
                );

        annotation.param("key", metadata.getKey());
        annotation.param("value", metadata.getValue());
    }
}


/*
========================================================
7. REGISTRY DES PROCESSORS
========================================================
*/

// AnnotationProcessorRegistry.java

package com.example.xjc.registry;

import com.example.xjc.metadata.Metadata;
import com.example.xjc.processor.AnnotationProcessor;
import com.example.xjc.processor.JsonMetaProcessor;
import com.example.xjc.processor.CustomPropertyProcessor;

import com.sun.codemodel.JFieldVar;
import com.sun.tools.xjc.outline.Outline;

import java.util.HashMap;
import java.util.Map;

public class AnnotationProcessorRegistry {

    private final Map<String, AnnotationProcessor> processors =
            new HashMap<>();

    public AnnotationProcessorRegistry() {

        register(new JsonMetaProcessor());

        register(new CustomPropertyProcessor());
    }

    public void register(AnnotationProcessor processor) {

        processors.put(processor.getType(), processor);
    }

    public void process(
            JFieldVar field,
            Metadata metadata,
            Outline outline) {

        AnnotationProcessor processor =
                processors.get(metadata.getType());

        if (processor == null) {
            return;
        }

        processor.process(field, metadata, outline);
    }
}


/*
========================================================
8. PLUGIN XJC PRINCIPAL
========================================================
*/

// CustomAnnotationPlugin.java

package com.example.xjc;

import com.example.xjc.metadata.Metadata;
import com.example.xjc.metadata.MetadataReader;
import com.example.xjc.registry.AnnotationProcessorRegistry;

import com.sun.tools.xjc.Plugin;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import com.sun.tools.xjc.outline.ClassOutline;
import com.sun.tools.xjc.model.CPropertyInfo;
import com.sun.codemodel.JFieldVar;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import java.util.List;

public class CustomAnnotationPlugin extends Plugin {

    private final MetadataReader metadataReader =
            new MetadataReader();

    private final AnnotationProcessorRegistry registry =
            new AnnotationProcessorRegistry();

    @Override
    public String getOptionName() {
        return "Xcustom-annotations";
    }

    @Override
    public String getUsage() {
        return "  -Xcustom-annotations : Generate custom annotations";
    }

    @Override
    public boolean run(
            Outline outline,
            Options options,
            ErrorHandler errorHandler
    ) throws SAXException {

        for (ClassOutline classOutline : outline.getClasses()) {

            for (CPropertyInfo property :
                    classOutline.target.getProperties()) {

                JFieldVar field =
                        classOutline.implClass.fields()
                                .get(property.getName(false));

                if (field == null) {
                    continue;
                }

                List<Metadata> metadata =
                        metadataReader.read(property);

                for (Metadata item : metadata) {

                    registry.process(
                            field,
                            item,
                            outline
                    );
                }
            }
        }

        return true;
    }
}


/*
========================================================
9. ENREGISTREMENT DU PLUGIN
========================================================

Fichier :

META-INF/services/com.sun.tools.xjc.Plugin

Contenu :

com.example.xjc.CustomAnnotationPlugin

========================================================
10. DEPENDANCE MAVEN
========================================================

<dependency>
    <groupId>org.glassfish.jaxb</groupId>
    <artifactId>jaxb-xjc</artifactId>
    <version>4.0.5</version>
    <scope>provided</scope>
</dependency>

========================================================
11. ACTIVATION XJC
========================================================

<arguments>
    <argument>-Xcustom-annotations</argument>
</arguments>

*/

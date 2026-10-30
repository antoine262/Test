package com.example.jsonschemaplugin;

import org.w3c.dom.Element;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Configuration du plugin : dans quels namespaces et quelles balises lire dans xs:appinfo,
 * et comment en tirer (clé, valeur). Tout est dans un fichier .properties.
 */
final class PluginConfig {
    record Entry(String key, String value) {}

    private final Set<String> namespaces;           // namespace(s) des balises : celui du xmlns par défaut du XSD
    private final boolean failOnUnknownTag;
    private final String annotation;
    private final String keyAttribute;
    private final String valueAttribute;
    private final Set<String> genericTags;          // <customProp key="title" value="..."/>
    private final Map<String, String> namedTags;    // <titre value="..."/>  (balise "titre" -> clé "title")

    private PluginConfig(Set<String> namespaces, boolean failOnUnknownTag, String annotation,
                         String keyAttribute, String valueAttribute,
                         Set<String> genericTags, Map<String, String> namedTags) {
        this.namespaces = namespaces;
        this.failOnUnknownTag = failOnUnknownTag;
        this.annotation = annotation;
        this.keyAttribute = keyAttribute;
        this.valueAttribute = valueAttribute;
        this.genericTags = genericTags;
        this.namedTags = namedTags;
    }

    /** Sans fichier de configuration : balise <customProp key= value=/> dans le namespace http://example.com/jsonschema. */
    static PluginConfig defaults() {
        return new PluginConfig(Set.of("http://example.com/jsonschema"), false,
                "com.example.jsonschema.JsonSchemaProperty", "key", "value", Set.of("customProp"), Map.of());
    }

    static PluginConfig load(File file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) { p.load(r); }

        String ns = p.getProperty("namespace");
        if (ns == null || ns.isBlank()) {
            throw new IOException(file + " : la propriété 'namespace' est obligatoire "
                    + "(le xmlns par défaut de votre XSD, celui des balises <customProp>)");
        }
        Map<String, String> named = new LinkedHashMap<>();
        for (String name : p.stringPropertyNames()) {
            if (name.startsWith("named.")) named.put(name.substring("named.".length()), p.getProperty(name).trim());
        }
        return new PluginConfig(
                split(ns),
                "error".equalsIgnoreCase(p.getProperty("unknown-tags", "warn").trim()),
                p.getProperty("annotation", "com.example.jsonschema.JsonSchemaProperty").trim(),
                p.getProperty("key-attribute", "key").trim(),
                p.getProperty("value-attribute", "value").trim(),
                split(p.getProperty("generic-tags", "customProp")),
                named);
    }

    private static Set<String> split(String csv) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : csv.split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    Set<String> namespaces() { return namespaces; }
    String annotation() { return annotation; }
    boolean failOnUnknownTag() { return failOnUnknownTag; }

    boolean isOurNamespace(String uri) { return namespaces.contains(uri == null ? "" : uri); }

    /**
     * Lit une balise de xs:appinfo. C'est le seul endroit à modifier pour supporter une autre syntaxe.
     * @return empty si la balise n'est pas déclarée dans la configuration
     */
    Optional<Entry> read(Element e) {
        String tag = e.getLocalName();

        if (genericTags.contains(tag)) {
            if (!e.hasAttribute(keyAttribute) || !e.hasAttribute(valueAttribute)) {
                throw new IllegalArgumentException("<" + tag + "> doit avoir les attributs '"
                        + keyAttribute + "' et '" + valueAttribute + "'");
            }
            return Optional.of(new Entry(e.getAttribute(keyAttribute), e.getAttribute(valueAttribute)));
        }

        String key = namedTags.get(tag);
        if (key != null) {
            String value = e.hasAttribute(valueAttribute) ? e.getAttribute(valueAttribute) : e.getTextContent().trim();
            return Optional.of(new Entry(key, value));
        }
        return Optional.empty();
    }
}

package com.example.jsonschemaplugin;

import com.sun.codemodel.JAnnotatable;
import com.sun.codemodel.JClass;
import com.sun.codemodel.JFieldVar;
import com.sun.tools.xjc.BadCommandLineException;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.Plugin;
import com.sun.tools.xjc.model.CCustomizations;
import com.sun.tools.xjc.model.CPluginCustomization;
import com.sun.tools.xjc.model.CPropertyInfo;
import com.sun.tools.xjc.outline.ClassOutline;
import com.sun.tools.xjc.outline.Outline;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Lit les balises de xs:appinfo (namespaces de la configuration) et pose @JsonSchemaProperty(key, value)
 * sur les classes et champs générés.
 *
 * Options, dans CET ordre : -XjsonSchema-config <fichier.properties>  puis  -XjsonSchema
 * (XJC collecte les namespaces du plugin au moment où il lit -XjsonSchema). XJC doit aussi recevoir -extension.
 */
public class JsonSchemaPlugin extends Plugin {
    private PluginConfig config = PluginConfig.defaults();
    private boolean activated;

    @Override public String getOptionName() { return "XjsonSchema"; }

    @Override
    public String getUsage() {
        return "  -XjsonSchema-config <f> : fichier .properties (namespace, balises, attributs) - à placer AVANT -XjsonSchema\n"
             + "  -XjsonSchema            : xs:appinfo -> @JsonSchemaProperty (nécessite aussi -extension)\n";
    }

    @Override
    public void onActivated(Options opts) {
        activated = true;
    }

    @Override
    public int parseArgument(Options opt, String[] args, int i) throws BadCommandLineException, IOException {
        if ("-XjsonSchema-config".equals(args[i])) {
            if (activated) {
                throw new BadCommandLineException("-XjsonSchema-config doit être placé AVANT -XjsonSchema : "
                        + "XJC lit les namespaces du plugin dès l'activation");
            }
            config = PluginConfig.load(new File(opt.requireArgument("-XjsonSchema-config", args, ++i)));
            return 2;
        }
        return 0;
    }

    /** Dit à XJC de conserver, pour ce plugin, les éléments de ces namespaces dans xs:appinfo. */
    @Override public List<String> getCustomizationURIs() { return List.copyOf(config.namespaces()); }

    @Override public boolean isCustomizationTagName(String nsUri, String localName) {
        return config.isOurNamespace(nsUri);
    }

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler eh) throws SAXException {
        JClass annotation = outline.getCodeModel().ref(config.annotation());
        boolean ok = true;

        for (ClassOutline co : outline.getClasses()) {
            ok &= annotate(co.implClass, co.target.getCustomizations(), annotation, eh);   // appinfo d'un type

            for (CPropertyInfo prop : co.target.getProperties()) {                         // appinfo d'un élément / attribut
                JFieldVar field = co.implClass.fields().get(prop.getName(false));
                if (field == null) field = co.implClass.fields().get("_" + prop.getName(false));
                if (field != null) ok &= annotate(field, prop.getCustomizations(), annotation, eh);
            }
        }
        return ok;
    }

    private boolean annotate(JAnnotatable target, CCustomizations customizations, JClass annotation,
                             ErrorHandler eh) throws SAXException {
        boolean ok = true;
        for (CPluginCustomization c : customizations) {
            if (!config.isOurNamespace(c.element.getNamespaceURI())) continue;   // customization d'un autre plugin
            c.markAsAcknowledged();                                              // sinon XJC refuse la génération

            try {
                Optional<PluginConfig.Entry> entry = config.read(c.element);
                if (entry.isPresent()) {
                    target.annotate(annotation).param("key", entry.get().key()).param("value", entry.get().value());
                } else {
                    String msg = "[XjsonSchema] balise non déclarée dans la configuration : <" + c.element.getLocalName() + ">";
                    if (config.failOnUnknownTag()) { eh.error(new SAXParseException(msg, c.locator)); ok = false; }
                    else eh.warning(new SAXParseException(msg, c.locator));
                }
            } catch (IllegalArgumentException ex) {
                eh.error(new SAXParseException("[XjsonSchema] " + ex.getMessage(), c.locator));
                ok = false;
            }
        }
        return ok;
    }
}

# Namespace des balises = le xmlns par défaut déclaré sur <xs:schema> de votre XSD
# (celui qui s'applique à <customProp> écrit sans préfixe). Plusieurs valeurs possibles, séparées par des virgules.
namespace=http://example.com/order

# Balise inconnue dans ce namespace : warn (défaut) ou error
unknown-tags=warn

# Annotation posée sur les classes / champs générés
annotation=com.example.jsonschema.JsonSchemaProperty

# Attributs lus dans les balises
key-attribute=key
value-attribute=value

# Balises génériques : <customProp key="title" value="..."/>  (plusieurs noms possibles, séparés par des virgules)
generic-tags=customProp

# Balises dont le nom donne la clé : named.<balise>=<clé>
#   <titre value="Email du client"/>  ->  @JsonSchemaProperty(key = "title", value = "Email du client")
named.titre=title
named.description=description



        <configuration>
          <schemaDirectory>${project.basedir}/src/main/resources/xsd</schemaDirectory>
          <generatePackage>com.example.model</generatePackage>
          <extension>true</extension>
          <forceRegenerate>true</forceRegenerate>
          <args>
            <arg>-XjsonSchema-config</arg>
            <arg>${project.basedir}/src/main/resources/jsonschema-plugin.properties</arg>
            <arg>-XjsonSchema</arg>
            <arg>-Xvalidation</arg>
            <arg>-Xvalidation-jsonignore</arg>
            <arg>-Xvalidation-rules</arg>
            <arg>${project.basedir}/src/main/resources/validation-rules.xml</arg>
          </args>
        </configuration>


<xs:element name="customerEmail" type="ShortEmail">
  <xs:annotation>
    <xs:appinfo>
      <customProp key="title" value="Email du client"/>
      <customProp key="format" value="email"/>
    </xs:appinfo>
  </xs:annotation>
</xs:element>

<xs:element name="notes" minOccurs="0">
  <xs:annotation>
    <xs:appinfo>
      <description value="Remarques libres, 5 mots maximum"/>   <!-- balise « nom = clé » -->
    </xs:appinfo>
  </xs:annotation>
  <!-- ... le xs:simpleType existant de notes ... -->
</xs:element>

  

  

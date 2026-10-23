package com.example.xjcval;

import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.*;

/** Lit validation-rules.xml : une règle = une annotation posée sur une classe ou un champ généré. */
final class RulesFile {
    /** fieldName == null : annotation posée sur la classe. */
    record Rule(String className, String fieldName, String annotation, Map<String, Object> params) {}

    static List<Rule> load(File file) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);   // pas de DTD / XXE
        Document doc = dbf.newDocumentBuilder().parse(file);

        List<Rule> rules = new ArrayList<>();
        NodeList targets = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < targets.getLength(); i++) {
            if (!(targets.item(i) instanceof Element target)) continue;
            String className, fieldName;
            switch (target.getTagName()) {
                case "class" -> { className = target.getAttribute("name"); fieldName = null; }
                case "field" -> { className = target.getAttribute("class"); fieldName = target.getAttribute("name"); }
                default -> throw new IllegalArgumentException("Balise inconnue <" + target.getTagName() + ">");
            }
            NodeList constraints = target.getElementsByTagName("constraint");
            for (int j = 0; j < constraints.getLength(); j++) {
                Element c = (Element) constraints.item(j);
                Map<String, Object> params = new LinkedHashMap<>();
                NamedNodeMap attrs = c.getAttributes();
                for (int k = 0; k < attrs.getLength(); k++) {
                    Node a = attrs.item(k);
                    if (!a.getNodeName().equals("annotation")) params.put(a.getNodeName(), typed(a.getNodeValue()));
                }
                rules.add(new Rule(className, fieldName, c.getAttribute("annotation"), params));
            }
        }
        return rules;
    }

    private static Object typed(String v) {
        if (v.equals("true") || v.equals("false")) return Boolean.parseBoolean(v);
        try { return Integer.valueOf(v); } catch (NumberFormatException e) { return v; }
    }
}

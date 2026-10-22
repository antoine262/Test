package com.example.xsdval.model;

import com.example.xsdval.xsd.Facets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Modèle neutre (sans dépendance XSOM) des contraintes extraites du XSD. */
public final class XsdModel {
    private XsdModel() {}

    /**
     * @param key        nom d'élément, "@nomAttribut" ou "#value"
     * @param minOccurs  occurrences minimales effectives (groupes parents inclus)
     * @param maxOccurs  occurrences maximales effectives, -1 = unbounded
     * @param facets     facettes du type simple (null si type complexe)
     * @param complexKey clé du type complexe référencé (null si type simple)
     */
    public record FieldInfo(String key, int minOccurs, int maxOccurs, Facets facets, String complexKey) {
        public boolean required() { return minOccurs >= 1; }
    }

    /** Un xs:choice non répétable dont tous les enfants sont des éléments. */
    public record ChoiceInfo(List<String> keys, boolean required) {}

    public static final class ClassInfo {
        private final String key;
        private final Map<String, FieldInfo> fields = new LinkedHashMap<>();
        private final List<ChoiceInfo> choices = new ArrayList<>();

        public ClassInfo(String key) { this.key = key; }
        public String key() { return key; }
        public Map<String, FieldInfo> fields() { return fields; }
        public List<ChoiceInfo> choices() { return choices; }
    }
}

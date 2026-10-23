package com.example.rules.core;

import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintValidatorContext.ConstraintViolationBuilder;
import jakarta.validation.ConstraintValidatorContext.ConstraintViolationBuilder.NodeBuilderCustomizableContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Collecte les violations d'une validation. Le chemin est une suite de noms de propriétés (String)
 * et d'indices (Integer) :  add(MESSAGE, "item", 3, "category")  =>  item[3].category
 */
public final class Violations {
    private record Entry(String message, Object[] path) {}

    private final List<Entry> entries = new ArrayList<>();

    /** message : modèle de ValidationMessages.properties, par exemple "{rules.order.duplicate-sku}". */
    public void add(String message, Object... path) {
        entries.add(new Entry(message, path));
    }

    public boolean isEmpty() { return entries.isEmpty(); }

    /** Chemins lisibles, utiles pour les tests et les logs. */
    public List<String> paths() {
        return entries.stream().map(e -> render(e.path())).toList();
    }

    /** Transmet les violations à Bean Validation, chacune rattachée à son champ. */
    public void writeTo(ConstraintValidatorContext ctx) {
        ctx.disableDefaultConstraintViolation();
        for (Entry e : entries) {
            ConstraintViolationBuilder builder = ctx.buildConstraintViolationWithTemplate(e.message());
            NodeBuilderCustomizableContext node = null;
            Integer pendingIndex = null;
            for (Object part : e.path()) {
                if (part instanceof Integer index) { pendingIndex = index; continue; }   // index du prochain noeud
                node = node == null ? builder.addPropertyNode((String) part) : node.addPropertyNode((String) part);
                if (pendingIndex != null) {
                    node = node.inIterable().atIndex(pendingIndex);
                    pendingIndex = null;
                }
            }
            if (node == null) builder.addConstraintViolation();
            else node.addConstraintViolation();
        }
    }

    private static String render(Object[] path) {
        StringBuilder sb = new StringBuilder();
        for (Object part : path) {
            if (part instanceof Integer i) sb.append('[').append(i).append(']');
            else {
                if (sb.length() > 0) sb.append('.');
                sb.append(part);
            }
        }
        return sb.toString();
    }
}

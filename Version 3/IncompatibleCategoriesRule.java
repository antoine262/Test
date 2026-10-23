package com.example.rules.order;

import com.example.model.Category;
import com.example.model.Item;
import com.example.model.Order;
import com.example.rules.core.Rule;
import com.example.rules.core.RuleSettings;
import com.example.rules.core.Violations;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Lignes entre elles : si une ligne de catégorie A est présente, les lignes de catégorie B sont interdites. */
public final class IncompatibleCategoriesRule implements Rule<Order> {
    private static final String MESSAGE = "{rules.order.incompatible-categories}";

    private record Pair(Category present, Category forbidden) {}

    private final List<Pair> pairs;

    public IncompatibleCategoriesRule(RuleSettings settings) {
        this.pairs = settings.list("incompatible-categories").stream().map(IncompatibleCategoriesRule::parse).toList();
    }

    /** "ALCOHOL:HAZMAT" -> Pair(ALCOHOL, HAZMAT) */
    private static Pair parse(String s) {
        String[] parts = s.split(":");
        if (parts.length != 2) throw new IllegalStateException("incompatible-categories : couple invalide '" + s + "'");
        return new Pair(Category.valueOf(parts[0].trim()), Category.valueOf(parts[1].trim()));
    }

    @Override
    public void check(Order order, Violations out) {
        // Un seul parcours des lignes : index des positions par catégorie.
        Map<Category, List<Integer>> positions = new EnumMap<>(Category.class);
        List<Item> items = order.getItem();
        for (int i = 0; i < items.size(); i++) {
            Category c = items.get(i).getCategory();
            if (c != null) positions.computeIfAbsent(c, k -> new ArrayList<>()).add(i);
        }
        for (Pair pair : pairs) {
            if (!positions.containsKey(pair.present())) continue;
            for (int index : positions.getOrDefault(pair.forbidden(), List.of())) {
                out.add(MESSAGE, "item", index, "category");
            }
        }
    }
}

package com.example.rules.order;

import com.example.model.Item;
import com.example.model.Order;
import com.example.rules.core.Rule;
import com.example.rules.core.Violations;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Élément contre éléments précédents : une référence ne peut apparaître qu'une fois (équivalent Java d'un xs:unique). */
public final class UniqueSkuRule implements Rule<Order> {
    private static final String MESSAGE = "{rules.order.duplicate-sku}";

    @Override
    public void check(Order order, Violations out) {
        Set<String> seen = new HashSet<>();
        List<Item> items = order.getItem();
        for (int i = 0; i < items.size(); i++) {
            String sku = items.get(i).getSku();
            if (sku != null && !seen.add(sku)) out.add(MESSAGE, "item", i, "sku");
        }
    }
}

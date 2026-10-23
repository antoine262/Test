package com.example.rules.order;

import com.example.model.Address;
import com.example.model.Category;
import com.example.model.Item;
import com.example.model.Order;
import com.example.rules.core.Rule;
import com.example.rules.core.RuleSettings;
import com.example.rules.core.Violations;

import java.util.List;
import java.util.Set;

/** Objet contre objet : une ligne ALCOHOL exige une destination dans un pays autorisé. */
public final class AlcoholDeliveryRule implements Rule<Order> {
    private static final String MESSAGE = "{rules.order.alcohol-delivery}";

    private final Set<String> allowedCountries;

    public AlcoholDeliveryRule(RuleSettings settings) {
        this.allowedCountries = settings.set("alcohol.allowed-countries");
    }

    @Override
    public void check(Order order, Violations out) {
        Address destination = order.getShipTo() != null ? order.getShipTo() : order.getBillTo();
        if (destination == null || destination.getCountry() == null) return;
        if (allowedCountries.contains(destination.getCountry().name())) return;

        List<Item> items = order.getItem();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getCategory() == Category.ALCOHOL) out.add(MESSAGE, "item", i, "category");
        }
    }
}

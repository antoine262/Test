package com.example.rules.order;

import com.example.model.Item;
import com.example.model.Order;
import com.example.rules.core.Rule;
import com.example.rules.core.RuleSettings;
import com.example.rules.core.Violations;

import java.math.BigDecimal;

/** Condition sur des valeurs : la somme (quantité x prix unitaire) ne dépasse pas le maximum configuré. */
public final class MaxOrderAmountRule implements Rule<Order> {
    private static final String MESSAGE = "{rules.order.max-amount}";

    private final BigDecimal maxAmount;

    public MaxOrderAmountRule(RuleSettings settings) {
        this.maxAmount = settings.decimal("order.max-amount");
    }

    @Override
    public void check(Order order, Violations out) {
        BigDecimal total = BigDecimal.ZERO;
        for (Item item : order.getItem()) {
            if (item.getQuantity() == null || item.getUnitPrice() == null) continue;
            total = total.add(item.getUnitPrice().multiply(new BigDecimal(item.getQuantity())));
        }
        if (total.compareTo(maxAmount) > 0) out.add(MESSAGE, "item");
    }
}

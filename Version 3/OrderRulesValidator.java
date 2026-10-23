package com.example.rules.order;

import com.example.model.Order;
import com.example.rules.core.Rule;
import com.example.rules.core.RuleSetValidator;
import com.example.rules.core.RuleSettings;

import java.util.List;

public class OrderRulesValidator extends RuleSetValidator<OrderRules, Order> {

    @Override
    protected List<Rule<Order>> createRules(RuleSettings settings) {
        return List.of(
                new AlcoholDeliveryRule(settings),
                new IncompatibleCategoriesRule(settings),
                new UniqueSkuRule(),
                new MaxOrderAmountRule(settings)
        );
    }
}

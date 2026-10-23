package com.example.rules.order;

import com.example.model.Category;
import com.example.model.Item;
import com.example.model.Order;
import com.example.rules.core.RuleSettings;
import com.example.rules.core.Violations;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Test unitaire d'une règle seule : sans Hibernate Validator, avec des réglages construits à la main. */
class IncompatibleCategoriesRuleTest {

    private static Item item(Category c) {
        Item i = new Item();
        i.setCategory(c);
        i.setQuantity(BigInteger.ONE);
        i.setUnitPrice(BigDecimal.TEN);
        return i;
    }

    @Test void flagsOnlyTheForbiddenCategory() {
        var rule = new IncompatibleCategoriesRule(RuleSettings.of(Map.of("incompatible-categories", "ALCOHOL:HAZMAT")));
        Order o = new Order();
        o.getItem().add(item(Category.ALCOHOL));
        o.getItem().add(item(Category.HAZMAT));
        o.getItem().add(item(Category.STANDARD));

        Violations v = new Violations();
        rule.check(o, v);
        assertThat(v.paths()).containsExactly("item[1].category");
    }

    @Test void severalPairsAreSupported() {
        var rule = new IncompatibleCategoriesRule(RuleSettings.of(
                Map.of("incompatible-categories", "ALCOHOL:HAZMAT, HAZMAT:STANDARD")));
        Order o = new Order();
        o.getItem().add(item(Category.HAZMAT));
        o.getItem().add(item(Category.STANDARD));

        Violations v = new Violations();
        rule.check(o, v);
        assertThat(v.paths()).containsExactly("item[1].category");
    }
}

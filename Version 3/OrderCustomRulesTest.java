package com.example.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static com.example.model.TestData.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Règles custom de la commande (OrderRules), exécutées par Hibernate Validator. */
class OrderCustomRulesTest {

    @Test void alcoholInAnAllowedCountryIsValid() {
        Order o = validOrder();
        o.getItem().get(0).setCategory(Category.ALCOHOL);
        assertThat(paths(o)).isEmpty();
    }

    @Test void alcoholAbroadIsRejectedOnTheItem() {
        Order o = validOrder();
        o.getBillTo().setCountry(Country.DE);
        o.getItem().get(0).setCategory(Category.ALCOHOL);
        assertThat(paths(o)).containsExactly("item[0].category");
    }

    @Test void shippingAddressOverridesBillingAddress() {
        Order o = validOrder();
        o.getBillTo().setCountry(Country.DE);
        o.setShipTo(address(Country.FR, "75001"));               // la livraison en France l'emporte
        o.getItem().get(0).setCategory(Category.ALCOHOL);
        assertThat(paths(o)).isEmpty();
    }

    @Test void incompatibleCategoriesAreRejectedOnTheForbiddenItems() {
        Order o = validOrder();
        o.getItem().get(0).setCategory(Category.ALCOHOL);
        o.getItem().add(item("ABC-0002", Category.HAZMAT, 2));
        assertThat(paths(o)).containsExactly("item[1].category");
    }

    @Test void duplicateSkuIsRejectedOnTheSecondItem() {
        Order o = validOrder();
        o.getItem().add(item("ABC-0001", null, 2));
        assertThat(paths(o)).containsExactly("item[1].sku");
    }

    @Test void totalAmountAboveTheLimitIsRejected() {
        Order o = validOrder();
        o.getItem().get(0).setQuantity(BigInteger.valueOf(1000));
        o.getItem().get(0).setUnitPrice(new BigDecimal("99.00"));   // 99 000 > 50 000
        assertThat(paths(o)).containsExactly("item");
    }

    @Test void violationsAreReportedTogether() {
        Order o = validOrder();
        o.getBillTo().setCountry(Country.DE);
        o.getItem().get(0).setCategory(Category.ALCOHOL);
        o.getItem().add(item("ABC-0001", Category.HAZMAT, 2));       // doublon + incompatible
        assertThat(paths(o)).contains("item[0].category", "item[1].category", "item[1].sku");
    }

    @Test void messagesComeFromValidationMessagesProperties() {
        Order o = validOrder();
        o.getItem().add(item("ABC-0001", null, 2));
        assertThat(VALIDATOR.validate(o)).extracting(v -> v.getMessage())
                .contains("Cette référence apparaît déjà dans la commande");
    }

    @Test void hundredsOfItemsStayFast() {
        Order o = validOrder();
        o.getItem().clear();
        for (int i = 0; i < 900; i++) o.getItem().add(item(String.format("ABC-%04d", i), null, i));
        long start = System.nanoTime();
        assertThat(paths(o)).isEmpty();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2000);   // large marge : détecte un O(n²) accidentel
    }
}

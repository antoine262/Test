package com.example.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static com.example.model.TestData.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Règles déduites du XSD par le plugin XJC. */
class OrderXsdRulesTest {

    @Test void validOrderHasNoViolation() {
        assertThat(VALIDATOR.validate(validOrder())).isEmpty();
    }

    @Test void emptyOrderReportsMissingFields() {
        assertThat(paths(new Order())).contains("customerEmail", "billTo", "item", "orderId", "choice1Valid");
    }

    @Test void facetsAreEnforced() {
        Order o = validOrder();
        o.setCustomerEmail("pas-un-email");                      // pattern hérité de Email
        o.setOrderId("abc");                                     // pattern Sku
        o.getItem().get(0).setQuantity(BigInteger.ZERO);         // positiveInteger => min 1
        o.getItem().get(0).setUnitPrice(new BigDecimal("0"));    // minExclusive 0
        assertThat(paths(o)).contains("customerEmail", "orderId", "item[0].quantity", "item[0].unitPrice");
    }

    @Test void derivedTypeUsesTheMostRestrictiveMaxLength() {
        Order o = validOrder();
        o.setCustomerEmail("a".repeat(45) + "@example.fr");      // > 50 (ShortEmail), < 254 (Email)
        assertThat(paths(o)).contains("customerEmail");
    }

    @Test void choiceAllowsExactlyOne() {
        Order o = validOrder();
        o.setCreditCard("ABC-1234");                             // iban + creditCard => invalide
        assertThat(paths(o)).contains("choice1Valid");
    }

    @Test void choiceWithSequenceBranch() {
        Order o = validOrder();
        o.setCarrier("DHL");                                     // trackingNo manquant
        assertThat(paths(o)).contains("choice2Valid");
        o.setTrackingNo("123");
        assertThat(paths(o)).doesNotContain("choice2Valid");
        o.setPickup("magasin");                                  // deux branches à la fois
        assertThat(paths(o)).contains("choice2Valid");
    }

    @Test void listElementFacetsAreEnforced() {
        Order o = validOrder();
        o.getTags().add("x".repeat(21));                         // maxLength 20 sur chaque tag
        assertThat(paths(o)).anyMatch(p -> p.startsWith("tags[0]"));
    }

    @Test void xsListFacets() {
        Order o = validOrder();
        for (int i = 0; i < 6; i++) o.getNotes().add("mot");     // > 5 mots
        o.getNotes().set(0, "x".repeat(11));                     // > 10 caractères
        assertThat(paths(o)).contains("notes").anyMatch(p -> p.startsWith("notes[0]"));
    }

    @Test void jsonIgnoreHidesChoiceMethods() throws Exception {
        String json = new ObjectMapper().writeValueAsString(validOrder());
        assertThat(json).doesNotContain("choice1Valid").contains("customerEmail");
    }
}

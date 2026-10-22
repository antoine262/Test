package com.example.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class OrderValidationTest {
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static Order validOrder() {
        Address a = new Address();
        a.setStreet("1 rue de Rivoli");
        a.setZip("75001");
        a.setCity("Paris");
        a.setCountry(Country.FR);

        Item i = new Item();
        i.setSku("ABC-1234");
        i.setQuantity(BigInteger.TEN);
        i.setUnitPrice(new BigDecimal("12.50"));
        i.setItemId(1);

        Order o = new Order();
        o.setCustomerEmail("client@example.fr");
        o.setBillTo(a);
        o.getItem().add(i);
        o.setIban("FR".repeat(1) + "x".repeat(25));   // 27 caractères
        o.setOrderId("ABC-1234");
        return o;
    }

    private static Set<String> paths(Order o) {
        return VALIDATOR.validate(o).stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @Test void validOrderHasNoViolation() {
        assertThat(VALIDATOR.validate(validOrder())).isEmpty();
    }

    @Test void emptyOrderReportsMissingFields() {
        assertThat(paths(new Order())).contains("customerEmail", "billTo", "item", "orderId", "choice1Valid");
    }

    @Test void facetsAreEnforced() {
        Order o = validOrder();
        o.setCustomerEmail("pas-un-email");                      // pattern
        o.setOrderId("abc");                                     // pattern Sku
        o.getItem().get(0).setQuantity(BigInteger.ZERO);         // positiveInteger => min 1
        o.getItem().get(0).setUnitPrice(new BigDecimal("0"));    // minExclusive 0
        assertThat(paths(o)).contains("customerEmail", "orderId", "item[0].quantity", "item[0].unitPrice");
    }

    @Test void choiceAllowsExactlyOne() {
        Order o = validOrder();
        o.setCreditCard("ABC-1234");                             // iban + creditCard => invalide
        assertThat(paths(o)).contains("choice1Valid");
    }

    @Test void jsonIgnoreHidesChoiceMethod() throws Exception {
        String json = new ObjectMapper().writeValueAsString(validOrder());
        assertThat(json).doesNotContain("choice1Valid").contains("customerEmail");
    }
}

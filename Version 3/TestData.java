package com.example.model;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Set;
import java.util.stream.Collectors;

/** Fabriques d'objets valides et validation, partagées par les tests. */
final class TestData {
    static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private TestData() {}

    static Address address(Country country, String zip) {
        Address a = new Address();
        a.setStreet("1 rue de Rivoli");
        a.setZip(zip);
        a.setCity("Paris");
        a.setCountry(country);
        return a;
    }

    static Item item(String sku, Category category, int id) {
        Item i = new Item();
        i.setSku(sku);
        i.setQuantity(BigInteger.ONE);
        i.setUnitPrice(new BigDecimal("10.00"));
        i.setCategory(category);
        i.setItemId(id);
        return i;
    }

    /** Commande valide pour toutes les règles (XSD + custom). */
    static Order validOrder() {
        Order o = new Order();
        o.setCustomerEmail("client@example.fr");
        o.setBillTo(address(Country.FR, "75001"));
        o.getItem().add(item("ABC-0001", null, 1));
        o.setIban("FR" + "x".repeat(25));              // 27 caractères
        o.setOrderId("ABC-1234");
        return o;
    }

    static User user(String department, String zip, Country country) {
        User u = new User();
        u.setName("Alice");
        u.setAddress(address(Country.FR, zip));
        u.setCountry(country);
        u.setDepartment(department);
        return u;
    }

    /** Chemins des champs en erreur, par exemple "item[0].quantity". */
    static Set<String> paths(Object bean) {
        return VALIDATOR.validate(bean).stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }
}

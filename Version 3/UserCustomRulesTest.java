package com.example.model;

import org.junit.jupiter.api.Test;

import static com.example.model.TestData.*;
import static org.assertj.core.api.Assertions.assertThat;

class UserCustomRulesTest {

    @Test void consistentDepartmentAndZipIsValid() {
        assertThat(paths(user("75", "75001", Country.FR))).isEmpty();
    }

    @Test void inconsistentZipIsRejectedOnTheZipField() {
        assertThat(paths(user("75", "13001", Country.FR))).containsExactly("address.zip");
    }

    @Test void referentialSupportsSeveralPrefixes() {
        assertThat(paths(user("2A", "20100", Country.FR))).isEmpty();
        assertThat(paths(user("2A", "20200", Country.FR))).containsExactly("address.zip");
    }

    @Test void ruleOnlyAppliesToConfiguredCountries() {
        assertThat(paths(user("75", "13001", Country.DE))).isEmpty();
    }

    @Test void ruleAddedOutsideTheXsdIsEnforced() {
        User u = user("75", "75001", Country.FR);
        u.getAddress().setCity("  ");                            // @NotBlank posé via validation-rules.xml
        assertThat(paths(u)).contains("address.city");
    }
}

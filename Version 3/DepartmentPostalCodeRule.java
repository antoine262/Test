package com.example.rules.user;

import com.example.model.User;
import com.example.rules.core.Rule;
import com.example.rules.core.RuleSettings;
import com.example.rules.core.Violations;

import java.util.Set;

/** Valeurs de plusieurs objets + référentiel : le code postal doit appartenir au département (pays configurés). */
public final class DepartmentPostalCodeRule implements Rule<User> {
    private static final String MESSAGE = "{rules.user.postal-code-department}";

    private final Set<String> checkedCountries;
    private final DepartmentReferential referential = DepartmentReferential.load();

    public DepartmentPostalCodeRule(RuleSettings settings) {
        this.checkedCountries = settings.set("user.department-check-countries");
    }

    @Override
    public void check(User user, Violations out) {
        if (user.getCountry() == null || !checkedCountries.contains(user.getCountry().name())) return;
        if (user.getDepartment() == null || user.getAddress() == null || user.getAddress().getZip() == null) return;

        if (!referential.accepts(user.getDepartment(), user.getAddress().getZip())) {
            out.add(MESSAGE, "address", "zip");               // l'erreur est rattachée au champ fautif
        }
    }
}

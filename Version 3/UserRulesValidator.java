package com.example.rules.user;

import com.example.model.User;
import com.example.rules.core.Rule;
import com.example.rules.core.RuleSetValidator;
import com.example.rules.core.RuleSettings;

import java.util.List;

public class UserRulesValidator extends RuleSetValidator<UserRules, User> {

    @Override
    protected List<Rule<User>> createRules(RuleSettings settings) {
        return List.of(new DepartmentPostalCodeRule(settings));
    }
}

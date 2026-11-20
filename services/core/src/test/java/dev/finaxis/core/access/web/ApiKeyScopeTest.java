package dev.finaxis.core.access.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link ApiKeyScope}, checked by a plain validator, without Spring. */
class ApiKeyScopeTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    record Body(List<@ApiKeyScope String> scopes) {}

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"balances:read", "charges:read", "charges:write", "refunds:write", "webhooks:manage"})
    void acceptsEveryApiKeyScope(String scope) {
        assertThat(violations(scope)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"api_keys:manage", "members:manage", "payouts:approve"})
    void rejectsAHumanOnlyPermission(String scope) {
        assertThat(violations(scope)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "charges", "CHARGES_READ", "Charges:Read", "charges:read "})
    void rejectsAnUnknownCode(String scope) {
        assertThat(violations(scope)).isNotEmpty();
    }

    @Test
    void namesTheFailingElement() {
        Set<ConstraintViolation<Body>> violations =
                VALIDATOR.validate(new Body(List.of("charges:read", "api_keys:manage")));

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath()).hasToString("scopes[1].<list element>");
            assertThat(violation.getMessage()).isEqualTo("must be an API key scope");
        });
    }

    @Test
    void leavesNullToNotNull() {
        assertThat(VALIDATOR.validate(new Body(Arrays.asList((String) null)))).isEmpty();
    }

    private static Set<ConstraintViolation<Body>> violations(String scope) {
        return VALIDATOR.validate(new Body(List.of(scope)));
    }
}

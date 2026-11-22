package dev.finaxis.core.web;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Refuses a number or a boolean for a text field, as {@code application.yml} refuses the other
 * coercions: Jackson reads {@code "name": 5} as {@code "5"} whatever the scalar coercion setting.
 */
@Configuration(proxyBeanMethods = false)
class StrictJsonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer noScalarAsText() {
        return builder -> builder.withCoercionConfig(
                LogicalType.Textual,
                text -> text.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}

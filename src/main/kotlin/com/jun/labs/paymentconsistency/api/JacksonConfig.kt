package com.jun.labs.paymentconsistency.api

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.cfg.CoercionAction
import com.fasterxml.jackson.databind.cfg.CoercionInputShape
import com.fasterxml.jackson.databind.type.LogicalType
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 정수 자리는 JSON 정수만 받는다 — 1.5 · "1000"은 읽기 실패(400 · ADR-000 §6.1) */
@Configuration
class JacksonConfig {
    @Bean
    fun strictIntegerCustomizer() = Jackson2ObjectMapperBuilderCustomizer { builder ->
        builder.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        builder.postConfigurer { mapper ->
            mapper.coercionConfigFor(LogicalType.Integer).apply {
                setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
            }
        }
    }
}

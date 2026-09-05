package com.mulinocoreano.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AuthConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class)
            .withPropertyValues("mulino.auth.audience=urn:mulino:erp-api");

    @Test
    void missingIssuerFailsStartupWithActionableConfigurationError() {
        context.run(result -> assertThat(result.getStartupFailure()).hasStackTraceContaining("MULINO_AUTH_ISSUER is required"));
    }

    @Test
    void insecureIssuerAndJwksOverridesAreRejected() {
        context.withPropertyValues("mulino.auth.issuer=http://tenant.example/")
                .run(result -> assertThat(result.getStartupFailure()).hasStackTraceContaining("MULINO_AUTH_ISSUER must use HTTPS"));
        context.withPropertyValues("mulino.auth.issuer=https://tenant.example/", "mulino.auth.jwks-uri=http://keys.example/jwks")
                .run(result -> assertThat(result.getStartupFailure()).hasStackTraceContaining("MULINO_AUTH_JWKS_URI must use HTTPS"));
    }

    @Test
    void serviceActionsStayUnconfiguredUnlessAWorkerClientIsSpecified() {
        context.withPropertyValues("mulino.auth.issuer=https://tenant.example/")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result.getBean(AuthProperties.class).workerClientId()).isEmpty();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthProperties.class)
    static class PropertiesOnly {}
}

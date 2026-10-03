package com.mulinocoreano.backend.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.http.HttpMethod;

@Configuration
@Profile("local")
@EnableConfigurationProperties(LocalAuthProperties.class)
public class LocalSecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain humanSecurity(HttpSecurity http, LocalActorDirectory directory) throws Exception {
        var paths = PathPatternRequestMatcher.withDefaults();
        return InterfaceSecurityConfiguration.stateless(http)
                .securityMatchers(matchers -> matchers
                        .requestMatchers(paths.matcher(HttpMethod.GET, "/api/v1/me"),
                                paths.matcher(HttpMethod.POST, "/api/v1/cases"),
                                paths.matcher(HttpMethod.POST, "/api/v1/cases/*/plans"),
                                paths.matcher(HttpMethod.GET, "/api/v1/plans/*")))
                .addFilterBefore(new LocalActorFilter(directory), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests.anyRequest().access((authentication, context) -> {
                    var actor = authentication.get().getPrincipal();
                    return new AuthorizationDecision(actor instanceof HumanActor human
                            && human.capabilities().contains(context.getRequest().getMethod().equals("POST")
                                    ? "work:write" : "erp:read"));
                }))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> response.setStatus(401))
                        .accessDeniedHandler((request, response, error) -> response.setStatus(403)))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain remainingInterfaceSecurity(HttpSecurity http) throws Exception {
        return InterfaceSecurityConfiguration.surface(http);
    }
}

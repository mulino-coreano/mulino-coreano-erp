package com.mulinocoreano.backend.security;

import com.mulinocoreano.backend.execution.DatabaseRunCapabilityAccess;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
@EnableConfigurationProperties(LocalAuthProperties.class)
public class LocalSecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain localAgentSecurity(HttpSecurity http, DatabaseRunCapabilityAccess capabilities)
            throws Exception {
        return http.securityMatcher(
                        "/api/v1/agent/**",
                        "/api/v1/cases/*/plans",
                        "/api/v1/plans/*/purchase-proposal")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .addFilterBefore(
                        new RunCapabilityFilter(capabilities), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(
                        requests ->
                                requests.requestMatchers(HttpMethod.POST, "/api/v1/cases/*/plans")
                                        .hasAuthority("agent:SUPPLY_CHAIN")
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/v1/plans/*/purchase-proposal")
                                        .hasAuthority("agent:PROCUREMENT")
                                        .requestMatchers(
                                                HttpMethod.POST, "/api/v1/agent/work-items")
                                        .hasAuthority("agent:ORCHESTRATOR")
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/v1/agent/work-items/*/transition")
                                        .authenticated()
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/v1/agent/cases/*",
                                                "/api/v1/agent/plans/*",
                                                "/api/v1/agent/materials/*",
                                                "/api/v1/agent/purchase-orders/*")
                                        .authenticated()
                                        .anyRequest()
                                        .denyAll())
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                        (request, response, error) -> response.sendError(401)))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain localApiSecurity(
            HttpSecurity http, LocalActorDirectory directory, LocalAuthProperties properties)
            throws Exception {
        var localActors = new LocalActorFilter(directory, properties);
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .addFilterBefore(localActors, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(
                        requests ->
                                requests.dispatcherTypeMatchers(DispatcherType.ERROR)
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/api/v1/attention/*/answer")
                                        .hasAuthority("work:write")
                                        .requestMatchers(HttpMethod.POST, "/api/v1/cases")
                                        .hasAuthority("work:write")
                                        .requestMatchers(
                                                HttpMethod.POST, "/api/v1/approvals/*/decision")
                                        .hasAuthority("procurement:decide")
                                        .requestMatchers(
                                                HttpMethod.POST, "/api/v1/internal/runs/**")
                                        .access(
                                                (authentication, context) ->
                                                        new AuthorizationDecision(
                                                                authentication.get().getPrincipal()
                                                                                instanceof ServiceActor service
                                                                        && service.capabilities()
                                                                                .contains("worker:dispatch")))
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/v1/events",
                                                "/api/v1/runs",
                                                "/api/v1/dispatch")
                                        .hasAuthority("worker:dispatch")
                                        .requestMatchers(HttpMethod.GET, "/api/v1/**")
                                        .hasAuthority("erp:read")
                                        .anyRequest()
                                        .denyAll())
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                        (request, response, error) -> response.sendError(401)))
                .build();
    }
}

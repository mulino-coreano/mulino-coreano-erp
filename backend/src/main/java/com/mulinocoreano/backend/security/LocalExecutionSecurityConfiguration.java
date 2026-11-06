package com.mulinocoreano.backend.security;

import com.mulinocoreano.backend.execution.DatabaseRunCapabilityAccess;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
@Profile("local")
public class LocalExecutionSecurityConfiguration {
    @Bean @Order(1)
    SecurityFilterChain agentSecurity(HttpSecurity http,DatabaseRunCapabilityAccess access) throws Exception {
        var paths=PathPatternRequestMatcher.withDefaults();
        var plan=paths.matcher(HttpMethod.POST,"/api/v1/cases/*/plans");
        var proposal=paths.matcher(HttpMethod.POST,"/api/v1/plans/*/purchase-proposal");
        var agent=paths.matcher("/api/v1/agent/**");
        return InterfaceSecurityConfiguration.stateless(http)
                .securityMatcher(req -> agent.matches(req)||proposal.matches(req)||(plan.matches(req)&&req.getHeader("Authorization")!=null))
                .addFilterBefore(new RunCapabilityFilter(access),AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.requestMatchers(plan).hasAuthority("agent:SUPPLY_CHAIN")
                        .requestMatchers(proposal).hasAuthority("agent:PROCUREMENT").anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex)->res.setStatus(401)))
                .build();
    }
    @Bean @Order(2)
    SecurityFilterChain workerSecurity(HttpSecurity http,LocalAuthProperties properties) throws Exception {
        var paths=PathPatternRequestMatcher.withDefaults();
        return InterfaceSecurityConfiguration.stateless(http)
                .securityMatchers(m->m.requestMatchers(paths.matcher("/api/v1/internal/runs/**"),
                        paths.matcher(HttpMethod.POST,"/api/v1/events"),paths.matcher(HttpMethod.POST,"/api/v1/runs"),
                        paths.matcher(HttpMethod.POST,"/api/v1/dispatch")))
                .addFilterBefore(new LocalServiceFilter(properties),AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth->auth.anyRequest().hasAuthority("worker:dispatch"))
                .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->res.setStatus(401))).build();
    }
}

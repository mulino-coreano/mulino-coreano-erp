package com.mulinocoreano.backend.security;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@Profile("!local")
public class InterfaceSecurityConfiguration {
    @Bean
    SecurityFilterChain defaultInterfaceSecurity(HttpSecurity http) throws Exception { return surface(http); }

    static HttpSecurity stateless(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable());
    }

    static SecurityFilterChain surface(HttpSecurity http) throws Exception {
        return stateless(http).authorizeHttpRequests(requests -> requests
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/ask", "/api/v1/cases", "/api/v1/cases/*",
                        "/api/v1/cases/*/work-items", "/api/v1/events", "/api/v1/attention",
                        "/api/v1/monitor", "/api/v1/health", "/api-docs", "/api-docs/**",
                        "/v3/api-docs", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/cases", "/api/v1/runs",
                        "/api/v1/events", "/api/v1/dispatch").permitAll()
                .anyRequest().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        (request, response, error) -> response.setStatus(403)))
                .build();
    }
}

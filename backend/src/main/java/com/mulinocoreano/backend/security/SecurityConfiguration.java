package com.mulinocoreano.backend.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfiguration {
    @Bean
    JwtDecoder jwtDecoder(AuthProperties properties) {
        return ErpJwtDecoder.create(properties.issuer(), properties.audience(), properties.jwksUri());
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ActorJwtConverter actors) throws Exception {
        var bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(requests -> requests
                        // 컨테이너의 오류 응답 전달만 허용한다. 직접 /error 요청은 아래 기본 거부를 유지한다.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/cases").hasAuthority("work:write")
                        .requestMatchers(HttpMethod.POST, "/api/v1/events", "/api/v1/runs", "/api/v1/dispatch")
                            .hasAuthority("worker:dispatch")
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").hasAuthority("erp:read")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(actors))
                        .authenticationEntryPoint((request, response, exception) -> {
                            if (exception instanceof OAuth2AuthenticationException oauth
                                    && ActorJwtConverter.ERP_ACCESS_DENIED.equals(oauth.getError().getErrorCode())) {
                                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                                response.setContentType("application/json");
                                response.getWriter().write("{\"error\":\"erp_access_denied\"}");
                            } else {
                                bearerEntryPoint.commence(request, response, exception);
                            }
                        }))
                .build();
    }
}

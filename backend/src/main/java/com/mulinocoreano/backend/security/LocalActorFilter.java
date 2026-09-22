package com.mulinocoreano.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * PoC 로컬 전용. {@code X-Mulino-Local-Role} 헤더(기본 MANAGER)로 HumanActor를 심는다.
 * Auth0 bearer는 쓰지 않는다. SecurityFilterChain에만 등록한다.
 */
public class LocalActorFilter extends OncePerRequestFilter {
    public static final String ROLE_HEADER = "X-Mulino-Local-Role";

    private final LocalActorDirectory directory;
    private final LocalAuthProperties properties;

    public LocalActorFilter(LocalActorDirectory directory, LocalAuthProperties properties) {
        this.directory = directory;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String role = request.getHeader(ROLE_HEADER);
        if (role == null || role.isBlank()) {
            role = properties.defaultRole();
        }
        try {
            HumanActor actor = directory.humanForRole(role);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new ActorAuthenticationToken(actor));
            SecurityContextHolder.setContext(context);
            filterChain.doFilter(request, response);
        } catch (IllegalArgumentException invalidRole) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter()
                    .write(
                            "{\"error\":\"invalid_local_role\",\"message\":\""
                                    + invalidRole.getMessage()
                                    + "\"}");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}

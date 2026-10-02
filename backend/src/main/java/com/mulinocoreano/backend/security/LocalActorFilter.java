package com.mulinocoreano.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** local 프로필의 인간 API에만 설치한다. 역할 헤더는 외부 인증이 아니다. */
public class LocalActorFilter extends OncePerRequestFilter {
    public static final String ROLE_HEADER = "X-Mulino-Local-Role";
    private final LocalActorDirectory directory;

    public LocalActorFilter(LocalActorDirectory directory) { this.directory = directory; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getHeader("Authorization") != null) {
            response.setStatus(401);
            return;
        }
        String role = request.getHeader(ROLE_HEADER);
        if (role == null || role.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        HumanActor actor;
        try {
            actor = directory.humanForRole(role);
        } catch (IllegalArgumentException invalidRole) {
            response.setStatus(400);
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new ActorAuthenticationToken(actor));
        SecurityContextHolder.setContext(context);
        try { chain.doFilter(request, response); }
        finally { SecurityContextHolder.clearContext(); }
    }
}

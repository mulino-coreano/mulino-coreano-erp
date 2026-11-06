package com.mulinocoreano.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * local 프로필의 인간 API에만 설치한다. 역할 헤더는 외부 인증이 아니다.
 * 모델 컨테이너도 백엔드에 닿으므로 역할 헤더는 호스트 인간 클라이언트만 가진
 * human secret과 함께 올 때만 인정한다. secret 미설정은 모든 역할 헤더를 거부한다.
 */
public class LocalActorFilter extends OncePerRequestFilter {
    public static final String ROLE_HEADER = "X-Mulino-Local-Role";
    public static final String HUMAN_SECRET_HEADER = "X-Mulino-Local-Human";
    private final LocalActorDirectory directory;
    private final LocalAuthProperties properties;

    public LocalActorFilter(LocalActorDirectory directory, LocalAuthProperties properties) {
        this.directory = directory;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getHeader("Authorization") != null || request.getHeader("X-Mulino-Local-Service") != null) {
            response.setStatus(401);
            return;
        }
        String role = request.getHeader(ROLE_HEADER);
        if (role == null || role.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        if (!hostHumanSecret(request.getHeader(HUMAN_SECRET_HEADER))) {
            response.setStatus(401);
            return;
        }
        HumanActor actor;
        try {
            var stored = directory.humanForRole(role);
            if (stored.isEmpty()) {
                response.setStatus(403);
                return;
            }
            actor = stored.get();
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

    private boolean hostHumanSecret(String presented) {
        String configured = properties.humanSecret();
        return configured != null && presented != null
                && MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), configured.getBytes(StandardCharsets.UTF_8));
    }
}

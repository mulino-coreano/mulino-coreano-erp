package com.mulinocoreano.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * PoC 로컬 신원. Auth0 없이 두 경로만 인정한다.
 *
 * <ul>
 *   <li>{@code Authorization: Bearer <worker token>} → 실행기 ServiceActor
 *   <li>{@code X-Mulino-Local-Role: <ROLE>} → 해당 역할의 HumanActor
 * </ul>
 *
 * 둘 다 없으면 인증하지 않고 넘겨 401이 나게 한다. 알 수 없는 bearer는 다른 신원으로 넘기지 않고
 * 바로 401로 거부한다. ponytail: 역할 헤더는 누구나 보낼 수 있으므로
 * 로컬 PoC 전용이다. 외부에 노출하려면 #21·#22의 실제 신원 공급자로 교체해야 한다.
 */
public class LocalActorFilter extends OncePerRequestFilter {
    public static final String ROLE_HEADER = "X-Mulino-Local-Role";
    static final String WORKER_CLIENT_ID = "local-worker";

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
        String authorization = request.getHeader("Authorization");
        if (authorization != null && !(authorization.startsWith("Bearer ") && isWorkerToken(authorization.substring(7)))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        ErpActor actor;
        try {
            actor = resolve(request);
        } catch (IllegalArgumentException invalidRole) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"invalid_local_role\"}");
            return;
        }
        if (actor == null) {
            filterChain.doFilter(request, response);
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new ActorAuthenticationToken(actor));
        SecurityContextHolder.setContext(context);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private ErpActor resolve(HttpServletRequest request) {
        if (request.getHeader("Authorization") != null) {
            return new ServiceActor(
                    properties.issuer(),
                    WORKER_CLIENT_ID + "@clients",
                    WORKER_CLIENT_ID,
                    Set.of("erp:read", "worker:dispatch"));
        }
        String role = request.getHeader(ROLE_HEADER);
        return role == null || role.isBlank() ? null : directory.humanForRole(role);
    }

    private boolean isWorkerToken(String presented) {
        String expected = properties.workerToken();
        return expected != null
                && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        presented.getBytes(StandardCharsets.UTF_8));
    }
}

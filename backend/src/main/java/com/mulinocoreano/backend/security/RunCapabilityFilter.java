package com.mulinocoreano.backend.security;

import com.mulinocoreano.backend.execution.DatabaseRunCapabilityAccess;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;

/** Opaque capabilities are accepted only by the dedicated agent chain, never JWT fallback. */
final class RunCapabilityFilter extends OncePerRequestFilter {
    private final DatabaseRunCapabilityAccess capabilities;
    RunCapabilityFilter(DatabaseRunCapabilityAccess capabilities) { this.capabilities=capabilities; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String authorization=request.getHeader("Authorization");
        try {
            if(authorization==null || !authorization.startsWith("Bearer ")) { response.sendError(401);return; }
            AgentActor actor=capabilities.authenticate(authorization.substring(7));
            var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(new ActorAuthenticationToken(actor));
            SecurityContextHolder.setContext(context);
        } catch(ResponseStatusException e) {
            SecurityContextHolder.clearContext();response.sendError(e.getStatusCode().value(),"Run capability rejected");return;
        }
        chain.doFilter(request,response);
    }
}

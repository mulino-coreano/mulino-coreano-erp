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

/** local 실행기 전용 비밀. 인간 역할과 동시에 제시할 수 없다. */
final class LocalServiceFilter extends OncePerRequestFilter {
    private final LocalAuthProperties properties;
    LocalServiceFilter(LocalAuthProperties properties) {this.properties=properties;}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)
            throws IOException,ServletException {
        String secret=req.getHeader("X-Mulino-Local-Service");
        if(secret==null||properties.serviceSecret()==null||req.getHeader(LocalActorFilter.ROLE_HEADER)!=null
                ||req.getHeader("Authorization")!=null
                ||!MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),properties.serviceSecret().getBytes(StandardCharsets.UTF_8))) {
            res.setStatus(401);return;
        }
        var ctx=SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new ActorAuthenticationToken(new ServiceActor(properties.issuer(),"local-worker","local-worker",Set.of("worker:dispatch"))));
        SecurityContextHolder.setContext(ctx);
        try {chain.doFilter(req,res);}finally{SecurityContextHolder.clearContext();}
    }
}

package com.mulinocoreano.backend.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Component
public class ActorJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    static final String ERP_ACCESS_DENIED = "erp_access_denied";
    private final ExternalIdentityRepository identities;
    private final AuthProperties properties;

    public ActorJwtConverter(ExternalIdentityRepository identities, AuthProperties properties) {
        this.identities = identities;
        this.properties = properties;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String issuer = jwt.getIssuer().toString();
        String subject = jwt.getSubject();
        String scopeClaim = jwt.getClaimAsString("scope");
        Set<String> scopes = scopeClaim == null ? Set.of() : Set.copyOf(Arrays.asList(scopeClaim.split(" +")));
        Set<String> capabilities = new HashSet<>();
        // Auth0 M2M의 gty 또는 표준 @clients subject를 먼저 판별한다.
        // 서비스 subject가 실수로 external_identities에 연결되어도 인간으로 승격하지 않는다.
        boolean service = "client-credentials".equals(jwt.getClaimAsString("gty")) || subject.endsWith("@clients");
        if (service) {
            if (!subject.endsWith("@clients")) throw accessDenied();
            String clientId = subject.substring(0, subject.length() - "@clients".length());
            if (clientId.isBlank()
                    || !matchesWhenPresent(jwt, "azp", clientId)
                    || !matchesWhenPresent(jwt, "client_id", clientId)) throw accessDenied();
            if (clientId.equals(properties.workerClientId())) {
                if (scopes.contains("erp:read")) capabilities.add("erp:read");
                if (scopes.contains("worker:dispatch")) capabilities.add("worker:dispatch");
            }
            return new ActorAuthenticationToken(new ServiceActor(issuer, subject, clientId, capabilities));
        }
        var user = identities.findActiveUser(issuer, subject).orElseThrow(ActorJwtConverter::accessDenied);
        if (scopes.contains("erp:read")) capabilities.add("erp:read");
        if (scopes.contains("work:write") && Set.of("OPERATOR", "MANAGER").contains(user.role())) {
            capabilities.add("work:write");
        }
        // 구매 결정은 토큰 scope와 현재 DB의 MANAGER 역할이 모두 필요하다.
        if (scopes.contains("procurement:decide") && "MANAGER".equals(user.role())) {
            capabilities.add("procurement:decide");
        }
        return new ActorAuthenticationToken(new HumanActor(issuer, subject, user.userId(), user.name(), user.role(), capabilities));
    }

    private static boolean matchesWhenPresent(Jwt jwt, String claim, String expected) {
        return !jwt.hasClaim(claim) || expected.equals(jwt.getClaimAsString(claim));
    }

    private static OAuth2AuthenticationException accessDenied() {
        return new OAuth2AuthenticationException(new OAuth2Error(ERP_ACCESS_DENIED),
                "ERP identity is not registered, inactive, or incompatible with the token actor type");
    }
}

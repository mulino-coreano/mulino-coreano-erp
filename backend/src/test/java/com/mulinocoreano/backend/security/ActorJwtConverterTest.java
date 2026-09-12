package com.mulinocoreano.backend.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

class ActorJwtConverterTest {
    final ExternalIdentityRepository identities = mock(ExternalIdentityRepository.class);
    final ActorJwtConverter converter = new ActorJwtConverter(identities,
            new AuthProperties("https://issuer.example/", "urn:mulino:erp-api", "", "worker"));
    Jwt token(String subject, String scope) {
        return Jwt.withTokenValue("fixture").header("alg", "RS256").issuer("https://issuer.example/")
                .subject(subject).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .claim("scope", scope).claim("roles", new String[]{"MANAGER"}).build();
    }
    void role(String role) { when(identities.findActiveUser("https://issuer.example/", "human"))
            .thenReturn(Optional.of(new ExternalIdentityRepository.RegisteredUser(1,"name",role))); }
    @Test void scopedManagerCanDecideButMissingScopeCannot() {
        role("MANAGER");
        assertThat(converter.convert(token("human","procurement:decide")).getAuthorities())
                .extracting("authority").containsExactly("procurement:decide");
        assertThat(converter.convert(token("human","erp:read work:write")).getAuthorities())
                .extracting("authority").doesNotContain("procurement:decide");
    }
    @ParameterizedTest @ValueSource(strings={"OPERATOR","ADMIN","QC","VIEWER"})
    void databaseRoleWinsOverForgedTokenRole(String role) {
        role(role);
        assertThat(converter.convert(token("human","erp:read procurement:decide")).getAuthorities())
                .extracting("authority").doesNotContain("procurement:decide");
    }
    @Test void serviceIdentityCannotGainHumanDecisionCapability() {
        assertThat(converter.convert(token("worker@clients","worker:dispatch procurement:decide")).getAuthorities())
                .extracting("authority").containsExactly("worker:dispatch");
        verifyNoInteractions(identities);
    }
    @Test void unregisteredOrInactiveIdentityCannotDecide() {
        when(identities.findActiveUser(anyString(),anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(()->converter.convert(token("human","procurement:decide")))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }
}

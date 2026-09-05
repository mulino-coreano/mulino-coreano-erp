package com.mulinocoreano.backend.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(JwtSecurityIntegrationTest.LocalJwks.class)
@Transactional
class JwtSecurityIntegrationTest {
    static final String ISSUER = "https://mulino-auth-test.example/";
    static final String AUDIENCE = "urn:mulino:erp-api";
    static final RSAKey SIGNING_KEY = signingKey();
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    String subject;
    long userId;

    @BeforeEach
    void registeredManager() {
        subject = "auth0|" + UUID.randomUUID();
        userId = jdbc.sql("""
                INSERT INTO users (name,email,password,role)
                VALUES ('테스트 담당자', :email, NULL, 'MANAGER') RETURNING user_id
                """).param("email", UUID.randomUUID() + "@example.test").query(Long.class).single();
        jdbc.sql("INSERT INTO external_identities (issuer,subject,user_id) VALUES (:issuer,:subject,:userId)")
                .param("issuer", ISSUER).param("subject", subject).param("userId", userId).update();
    }

    @Test
    void managerIdentityUsesTheDatabaseRoleAndReturnsOnlyImplementedCapabilities() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(claims -> claims.claim("roles", new String[]{"ADMIN"}))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actorType").value("HUMAN"))
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.capabilities", containsInAnyOrder("erp:read", "work:write")));
    }

    @Test
    void roleChangeAndDeactivationTakeEffectOnTheNextRequestWithTheSameToken() throws Exception {
        String token = bearer(claims -> {});
        mvc.perform(get("/api/v1/me").header("Authorization", token)).andExpect(status().isOk());
        jdbc.sql("UPDATE users SET role='VIEWER' WHERE user_id=:id").param("id", userId).update();
        mvc.perform(get("/api/v1/me").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("VIEWER"))
                .andExpect(jsonPath("$.capabilities", not(hasItem("work:write"))));
        mvc.perform(post("/api/v1/cases").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"objective\":\"Denied\"}"))
                .andExpect(status().isForbidden());
        jdbc.sql("UPDATE users SET is_active=false WHERE user_id=:id").param("id", userId).update();
        mvc.perform(get("/api/v1/me").header("Authorization", token)).andExpect(status().isForbidden());
    }

    @Test
    void unknownSubjectCannotJoinByMatchingEmail() throws Exception {
        String email = jdbc.sql("SELECT email FROM users WHERE user_id=:id").param("id", userId).query(String.class).single();
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(claims -> claims
                        .subject("auth0|unregistered").claim("email", email))))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"VIEWER", "QC", "ADMIN"})
    void onlyOperatorsAndManagersMayCreateCases(String role) throws Exception {
        jdbc.sql("UPDATE users SET role=:role::user_role WHERE user_id=:id").param("role", role).param("id", userId).update();
        long count = jdbc.sql("SELECT count(*) FROM cases").query(Long.class).single();
        mvc.perform(post("/api/v1/cases").header("Authorization", bearer(claims -> {}))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"objective\":\"Denied\"}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT count(*) FROM cases").query(Long.class).single()).isEqualTo(count);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "OPERATOR"})
    void authorisedHumansCanCreateCases(String role) throws Exception {
        jdbc.sql("UPDATE users SET role=:role::user_role WHERE user_id=:id").param("role", role).param("id", userId).update();
        mvc.perform(post("/api/v1/cases").header("Authorization", bearer(claims -> {}))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"objective\":\"인증된 목표 접수\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.intentType").value("ACT"));
    }

    @Test
    void scopesAreRequiredEvenForAManager() throws Exception {
        mvc.perform(get("/api/v1/cases").header("Authorization", bearer(claims -> claims.claim("scope", "work:write"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/cases").header("Authorization", bearer(claims -> claims.claim("scope", "erp:read")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"objective\":\"Denied\"}"))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/events", "/api/v1/runs", "/api/v1/dispatch"})
    void humansCannotInvokePrivilegedDispatcherWrites(String path) throws Exception {
        mvc.perform(post(path).header("Authorization", bearer(claims -> claims.claim("scope", "erp:read work:write worker:dispatch procurement:decide")))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void serviceIdentityNeverUsesHumanMappingOrManagerClaims() throws Exception {
        jdbc.sql("INSERT INTO external_identities (issuer,subject,user_id) VALUES (:issuer,'test-worker@clients',:id)")
                .param("issuer", ISSUER).param("id", userId).update();
        String token = bearer(claims -> claims.subject("test-worker@clients")
                .claim("gty", "client-credentials").claim("azp", "test-worker")
                .claim("role", "MANAGER").claim("scope", "erp:read work:write procurement:decide worker:dispatch"));
        mvc.perform(get("/api/v1/me").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actorType").value("SERVICE"))
                .andExpect(jsonPath("$.clientId").value("test-worker"))
                .andExpect(jsonPath("$.role").doesNotExist()).andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.capabilities", not(hasItem("work:write"))));
        mvc.perform(post("/api/v1/cases").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"objective\":\"Denied\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void clientCredentialsWithHumanLookingSubjectCannotBecomeHuman() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(claims -> claims
                        .claim("gty", "client-credentials").claim("azp", "test-worker"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void serviceSubjectRemainsServiceWhenGrantClaimIsAbsent() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(claims -> claims
                        .subject("test-worker@clients").claim("azp", "test-worker"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actorType").value("SERVICE"));
    }

    @Test
    void workerScopeAndAllowlistedClientAreBothRequired() throws Exception {
        mvc.perform(post("/api/v1/dispatch").header("Authorization", bearer(claims -> claims
                        .subject("other-worker@clients").claim("azp", "other-worker")
                        .claim("gty", "client-credentials").claim("scope", "worker:dispatch"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/dispatch").header("Authorization", bearer(claims -> claims
                        .subject("test-worker@clients").claim("azp", "test-worker").claim("gty", "client-credentials"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/dispatch").header("Authorization", bearer(claims -> claims
                        .subject("test-worker@clients").claim("azp", "test-worker")
                        .claim("gty", "client-credentials").claim("scope", "worker:dispatch"))))
                .andExpect(status().isAccepted());
    }

    @Test
    void incorrectIssuerOrAudienceAndExpiredOrUnboundedTokensAreRejected() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(claims -> claims.issuer("https://wrong.example/"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(claims -> claims.audience("https://mcp.example/mcp"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(claims -> claims.expirationTime(Date.from(Instant.now().minusSeconds(120))))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(claims -> claims.expirationTime(null))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(claims -> claims.notBeforeTime(Date.from(Instant.now().plusSeconds(120))))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingAudienceOrSubjectAndMalformedScopeAreRejectedAsAuthenticationErrors() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(claims -> claims.audience((String) null))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(claims -> claims.subject(null))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(claims -> claims.claim("scope", new String[]{"erp:read"}))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mappingForAnotherIssuerCannotAuthenticateTheSameSubject() throws Exception {
        jdbc.sql("UPDATE external_identities SET issuer='https://another-tenant.example/' WHERE user_id=:id")
                .param("id", userId).update();
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(claims -> {})))
                .andExpect(status().isForbidden());
    }

    @Test
    void wrongSignatureAndUnsupportedAlgorithmAreRejected() throws Exception {
        String token = signed(claims -> {}, signingKey(), JWSAlgorithm.RS256);
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + signed(claims -> {}, SIGNING_KEY, JWSAlgorithm.RS512)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownWriteRoutesFailClosedForManagers() throws Exception {
        mvc.perform(post("/api/v1/approvals/1/decision").header("Authorization", bearer(claims -> {}))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    private String bearer(Consumer<JWTClaimsSet.Builder> customize) throws Exception {
        return "Bearer " + signed(customize, SIGNING_KEY, JWSAlgorithm.RS256);
    }

    private String signed(Consumer<JWTClaimsSet.Builder> customize, RSAKey key, JWSAlgorithm algorithm) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject).audience(AUDIENCE)
                .issueTime(Date.from(Instant.now().minusSeconds(10))).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("scope", "erp:read work:write procurement:decide");
        customize.accept(claims);
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(algorithm).keyID("test-key").type(JOSEObjectType.JWT).build(), claims.build());
        token.sign(new RSASSASigner(key));
        return token.serialize();
    }

    private static RSAKey signingKey() {
        try { return new RSAKeyGenerator(2048).keyID("test-key").generate(); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalJwks {
        @Bean(destroyMethod = "stop")
        JwksServer jwksServer() throws Exception { return new JwksServer(); }

        @Bean
        @Primary
        JwtDecoder localJwtDecoder(JwksServer server) {
            return ErpJwtDecoder.create(ISSUER, AUDIENCE, server.uri());
        }
    }

    static final class JwksServer {
        private final HttpServer server;
        JwksServer() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            server.start();
        }
        String uri() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks"; }
        public void stop() { server.stop(0); }
    }
}

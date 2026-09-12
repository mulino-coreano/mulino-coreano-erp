package com.mulinocoreano.backend.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 servlet ERROR dispatch를 실행한다. MockMvc는 이 컨테이너 경로를 재현하지 않는다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(JwtSecurityIntegrationTest.LocalJwks.class)
class HttpErrorSecurityIntegrationTest {
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void authenticatedUnknownApiRouteRetainsItsNotFoundStatus() throws Exception {
        var response = send("GET", "/api/v1/nonexistent", true);
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/events", "/api/v1/runs"})
    void authenticatedWorkerValidationFailuresRetainTheirBadRequestStatus(String path) throws Exception {
        var response = send("POST", path, true);
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void directErrorRequestsRemainForbiddenForAuthenticatedCallers() throws Exception {
        assertThat(send("GET", "/error", true).statusCode()).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/error", "/api/v1/nonexistent"})
    void errorDispatchPermissionDoesNotAllowAnonymousRequests(String path) throws Exception {
        var response = send("GET", path, false);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate").orElseThrow()).startsWith("Bearer");
    }

    private HttpResponse<String> send(String method, String path, boolean authenticated) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (authenticated) request.header("Authorization", "Bearer " + workerToken());
        if ("POST".equals(method)) {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
        } else {
            request.GET();
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String workerToken() throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .issuer(JwtSecurityIntegrationTest.ISSUER)
                .audience(JwtSecurityIntegrationTest.AUDIENCE)
                .subject("test-worker@clients")
                .claim("gty", "client-credentials")
                .claim("azp", "test-worker")
                .claim("scope", "erp:read worker:dispatch")
                .issueTime(Date.from(Instant.now().minusSeconds(10)))
                .expirationTime(Date.from(Instant.now().plusSeconds(300))).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
        jwt.sign(new RSASSASigner(JwtSecurityIntegrationTest.SIGNING_KEY));
        return jwt.serialize();
    }
}

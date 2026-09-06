package com.mulinocoreano.backend.demo;

import static org.assertj.core.api.Assertions.assertThat;
import com.mulinocoreano.backend.planning.ReplenishmentDemoFixture;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

@Tag("demo-e2e")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.flyway.schemas=demo_e2e", "spring.flyway.clean-disabled=false",
    "spring.flyway.init-sqls=CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public",
    "spring.datasource.hikari.schema=demo_e2e", "spring.main.allow-bean-definition-overriding=true"})
class DemoE2eTest {
    static final Path temp;
    static final SSLContext originalSsl;
    static final SSLSocketFactory originalSocketFactory;
    static final HttpsServer jwks;
    static final RSAKey key;
    static {
        try {
            originalSsl = SSLContext.getDefault();
            originalSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
            String db = System.getenv("DB_URL");
            if (db == null || !db.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/mulino_demo_e2e"))
                throw new IllegalStateException("Requires explicit loopback mulino_demo_e2e disposable database");
            temp = Files.createTempDirectory("mulino-demo-e2e-");
            run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", temp.resolve("key.pem").toString(), "-out", temp.resolve("cert.pem").toString(), "-days", "1", "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1");
            run("openssl", "pkcs12", "-export", "-inkey", temp.resolve("key.pem").toString(), "-in", temp.resolve("cert.pem").toString(), "-out", temp.resolve("tls.p12").toString(), "-passout", "pass:fixture-only");
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(temp.resolve("tls.p12"))) { ks.load(in, "fixture-only".toCharArray()); }
            KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            km.init(ks, "fixture-only".toCharArray());
            TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(ks);
            SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(km.getKeyManagers(), tm.getTrustManagers(), null);
            SSLContext.setDefault(ssl); HttpsURLConnection.setDefaultSSLSocketFactory(ssl.getSocketFactory());
            key = new RSAKeyGenerator(2048).keyID("demo-fixture").generate();
            jwks = HttpsServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            jwks.setHttpsConfigurator(new HttpsConfigurator(ssl));
            jwks.createContext("/jwks", exchange -> { byte[] bytes = ("{\"keys\":[" + key.toPublicJWK().toJSONString() + "]}").getBytes(java.nio.charset.StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length); try(var out=exchange.getResponseBody()){out.write(bytes);} });
            jwks.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    static void run(String... command) throws Exception { var p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start(); if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue()!=0) throw new IllegalStateException("Fixture prerequisite failed: " + command[0]); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("mulino.auth.issuer", () -> "https://demo-auth.invalid/");
        r.add("mulino.auth.jwks-uri", () -> "https://localhost:" + jwks.getAddress().getPort() + "/jwks");
        r.add("mulino.auth.worker-client-id", () -> "demo-worker");
    }
    static final java.util.concurrent.atomic.AtomicReference<Instant> businessNow = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-09-05T00:00:00Z"));
    @TestConfiguration static class Time {
        @Bean("planningClock") @Primary Clock clock() { return new Clock() { public ZoneId getZone(){ return ZoneId.of("Asia/Seoul"); } public Clock withZone(ZoneId zone){ return Clock.fixed(instant(),zone); } public Instant instant(){return businessNow.get();} }; }
    }
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired Flyway flyway;
    @Autowired ObjectMapper mapper;
    @Test void actualHttpMcpRunnerCliPurchaseAcceptance() throws Exception {
        assertThat(jdbc.sql("SHOW server_version").query(String.class).single()).startsWith("18.");
        assertThat(flyway.getConfiguration().getSchemas()).containsExactly("demo_e2e");
        resetFixture();
        long warehouse = jdbc.sql("SELECT warehouse_id FROM warehouses WHERE plant_id='DEMO-KR-01'").query(Long.class).single();
        var products = jdbc.sql("SELECT product_id FROM products WHERE sku IN ('DEMO-AMR','DEMO-BSC') ORDER BY product_id").query(Long.class).list();
        Path config = temp.resolve("scenario.json");
        Files.writeString(config, mapper.writeValueAsString(Map.of("api", "http://127.0.0.1:"+port+"/api/v1", "privateJwk", key.toJSONObject(), "warehouseId", warehouse, "productIds", products)));
        Path root = Path.of("..").toAbsolutePath().normalize();
        assertThat(Files.isExecutable(root.resolve("agents/cli/zig-out/bin/mulino"))).as("Run zig build in agents/cli first").isTrue();
        launch(root, config, "block");
        assertThat(jdbc.sql("SELECT count(*) FROM purchase_orders WHERE purchase_application_id IS NOT NULL").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM replenishment_followups").query(Long.class).single()).isZero();
        resetFixture();
        launch(root, config, "approve");
        assertThat(jdbc.sql("SELECT due_at FROM replenishment_followups").query(java.time.OffsetDateTime.class).single().toInstant()).isEqualTo(Instant.parse("2026-09-07T15:00:00Z"));
        assertThat(jdbc.sql("SELECT trim_scale(i.quantity)::text || ':' || trim_scale(i.unit_price)::text FROM purchase_order_items i JOIN purchase_orders p USING(purchase_order_id) WHERE p.purchase_application_id IS NOT NULL ORDER BY i.raw_material_id").query(String.class).list()).containsExactly("5:1200", "5:1500", "60:50");
        businessNow.set(Instant.parse("2026-09-08T00:00:00Z"));
        launch(root, config, "due");
        assertThat(jdbc.sql("SELECT count(*) FROM purchase_orders WHERE purchase_application_id IS NOT NULL").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT sum(i.quantity*i.unit_price) FROM purchase_order_items i JOIN purchase_orders p USING(purchase_order_id) WHERE p.purchase_application_id IS NOT NULL").query(java.math.BigDecimal.class).single()).isEqualByComparingTo("16500");
        assertThat(jdbc.sql("SELECT count(*) FROM replenishment_followups").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM runs r JOIN replenishment_followups f ON f.work_item_id=r.work_item_id").query(Long.class).single()).isZero();
    }
    void resetFixture() throws Exception {
        assertThat(flyway.getConfiguration().getSchemas()).containsExactly("demo_e2e");
        flyway.clean(); flyway.migrate(); ReplenishmentDemoFixture.load(jdbc);
        for (String role : List.of("MANAGER", "OPERATOR")) {
            long id = jdbc.sql("INSERT INTO users(name,email,role) VALUES(:name,:email,CAST(:role AS user_role)) RETURNING user_id").param("name", "demo-" + role).param("email", role+"@demo.invalid").param("role", role).query(Long.class).single();
            jdbc.sql("INSERT INTO external_identities(issuer,subject,user_id) VALUES('https://demo-auth.invalid/',:sub,:id)").param("sub", "auth0|"+role).param("id", id).update();
        }
    }
    void launch(Path root, Path config, String phase) throws Exception {
        Path log = temp.resolve("scenario-" + phase + ".log");
        var pb = new ProcessBuilder("node", root.resolve("mcp-server/scripts/demo-e2e/scenario.mjs").toString(), config.toString(), phase);
        pb.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        String path = pb.environment().get("PATH"); pb.environment().clear(); pb.environment().put("PATH", path);
        var process = pb.start();
        if (!process.waitFor(180, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("E2E process timeout: " + phase); }
        System.out.println(Files.readString(log));
        assertThat(process.exitValue()).as(phase).isZero();
    }
    @AfterAll static void cleanup() throws Exception { jwks.stop(0); SSLContext.setDefault(originalSsl); HttpsURLConnection.setDefaultSSLSocketFactory(originalSocketFactory); try(var paths=Files.walk(temp)){for(var p:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);} }
}

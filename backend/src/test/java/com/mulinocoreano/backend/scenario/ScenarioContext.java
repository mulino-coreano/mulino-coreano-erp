package com.mulinocoreano.backend.scenario;

import io.cucumber.spring.CucumberContextConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.schemas=scenario", "spring.flyway.clean-disabled=false",
        "spring.flyway.init-sqls=CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public",
        "spring.datasource.hikari.schema=scenario", "spring.main.allow-bean-definition-overriding=true",
        "mulino.local-auth.worker-token=" + ScenarioContext.WORKER_TOKEN})
public class ScenarioContext {
    public static final String WORKER_TOKEN = "scenario-worker-token";
    public static final Instant BUSINESS_NOW = Instant.parse("2026-09-05T00:00:00Z");

    static boolean live() { return "live".equals(System.getProperty("mulino.scenario.agent")); }

    @DynamicPropertySource
    static void runtime(DynamicPropertyRegistry r) {
        // Flyway migrates the DB named by DB_URL as soon as the Spring context starts, which
        // happens before any @Test or @Before method runs. The guard must run here -- the
        // earliest hook this class offers -- so a non-disposable DB is rejected before Flyway
        // ever touches it, not just before the first scenario step.
        ScenarioGuard.requireDisposable(System.getenv("DB_URL"));
        // 서버가 예약하는 Run의 런타임은 UAT 실행기와 같아야 한다. SIT 스크립트 실행기는 기본값 CODEX로 claim한다.
        r.add("mulino.execution.runtime", () -> live() ? System.getenv().getOrDefault("MULINO_AGENT_RUNTIME", "CODEX") : "CODEX");
    }

    @TestConfiguration
    static class FixedClock {
        @Bean("planningClock") @Primary
        Clock planningClock() { return Clock.fixed(BUSINESS_NOW, ZoneId.of("Asia/Seoul")); }
    }
}

# 시나리오 테스트 체계 구현 계획

> **에이전트 작업자를 위한 안내:** 필수 서브스킬: superpowers:subagent-driven-development(권장)
> 또는 superpowers:executing-plans를 사용해 이 계획을 태스크 단위로 구현한다.
> 단계는 체크박스(`- [ ]`) 문법으로 진행 상황을 추적한다. 테스트를 작성하기 전에
> 반드시 저장소의 `testing` 스킬을 로드한다.

---

> **스펙과의 차이**
>
> 스펙 §5 예시 조건 "DEMO-AMR 가용 재고가 안전재고보다 적다"는 픽스처(가용 30 >
> 안전 14)와 맞지 않는다. 재고가 안전재고보다 많아도 30일 수요를 채우지 못하는
> 경우가 재보충 트리거다. 이 계획에서는 "DEMO-AMR 가용 재고 30으로는 30일 수요
> 60과 안전재고 14를 채우지 못한다"를 사용한다.

> **알려진 공백**
>
> - Task 7 Step 1에서 재시작 테스트를 전체 코드 대신 DemoE2eTest 참조로 서술한다.
>   구현 시 해당 파일을 직접 읽고 `HumanChannel`/`AgentDriver` 호출로 교체한다.
> - 두 SQL 컬럼명(`production_lots`의 만료일/상태 컬럼, `governance_actions.case_id`)은
>   DDL 확인이 필요하다. 해당 단계에 확인 지시가 명시되어 있다.

---

**목표:** 데모 인수 테스트를 SAP 방식 SIT/UAT 시나리오 스크립트(Cucumber, 한국어
Gherkin)로 교체한다. 재보충 프로세스(MRP → P2P)를 end-to-end로 증명하고, 같은
스크립트를 실제 에이전트 하네스로 UAT 실행하며, 품질·리콜 스크립트와 기존
테스트 정리를 준비한다.

**아키텍처:** `backend/src/test/resources/scenarios/`의 Gherkin 스크립트가 JUnit
Platform suite를 통해 백엔드 테스트 JVM 안에서 실행된다. Step definition은 역할별
실제 stdio MCP 프로세스(`human.mjs`)로 사람을 구동하고, 실제 runner 프로세스(SIT는
스크립트 에이전트, UAT는 실제 하네스)로 에이전트를 구동하며, SQL로 업무 상태를
단언한다. 백엔드는 실제 Spring Boot 서버이며 업무 시계는 픽스처 기준일로 고정된다.

**기술 스택:** Java 21, Spring Boot 4.1.1, Cucumber-JVM 8.0.1 (`cucumber-java`,
`cucumber-spring`, `cucumber-junit-platform-engine`), JUnit Platform Suite,
PostgreSQL 18, Node 22+, Zig 0.16.0 CLI, MCP SDK stdio client.

**스펙:** `docs/superpowers/specs/2026-09-25-scenario-tests-design.md` (PR #58). 이슈 #57.

## 전역 제약

- 베이스 브랜치: `feat/57-scenario-tests`는 `origin/feat/24-runtime-agnostic-execution`
  (PR #55)에서 분기한다 — UAT에 필요한 런타임 선택(`MULINO_AGENT_RUNTIME`/`MULINO_AGENT_MODEL`)과
  runner의 `model_finished` 로그가 해당 브랜치에만 존재한다. PR #58 브랜치를 병합해
  스펙과 `testing` 스킬을 가져온다.
- Cucumber 8.0.1 (Spring 7.0.9, JUnit 6 기준 빌드).
- 업무 시계는 `2026-09-05T00:00:00Z`(픽스처 기준일 2026-09-05, Asia/Seoul)로
  SIT와 UAT 모두 고정한다.
- 스크립트: `# language: ko`; 키워드 기능/시나리오/조건/만일/그러면/그리고;
  단계는 역할로 시작; `.feature` 파일에 URL/SQL/JSON/클래스명 금지.
- 단언은 업무 상태만 확인한다(발주 건수, 승인 상태, 제안/발주 합계, Case 상태,
  후속 업무, Run 횟수). 오류 메시지 문구, 라벨, JSON 필드 존재, 메서드 호출 금지.
- 프로세스당 3~6 케이스. 승인 게이트마다 승인·반려·권한 없음 케이스.
- 태그: `@TC-<PROC>-NNN`, `@sit`, `@uat`, SAP 모듈(`@MM`), 미구현 기능은
  `@pending @issue-N`.
- 명령: `./gradlew test`(Unit, 시나리오 제외), `./gradlew sitTest`(`@sit and not @pending`),
  `./gradlew uatTest`(`@uat and not @pending`, 비용 발생),
  `./gradlew pendingScenarios`(`@pending` dry-run 목록).
- SIT 에이전트 대기 ≤ 60초; UAT ≤ 15분. 타임아웃 시 실패 메시지에 기대 상태와
  마지막 `model_finished` 실패 코드를 포함한다.
- UAT 사전 조건 없음(로그인 볼륨, 이미지, 모델) → 시나리오 건너뜀, 실패 아님.
- UAT 증거: `build/uat/<yyyy-MM-dd>/<TC-ID>.json` (런타임, 모델, Run별 결과와
  실패 코드, 비용, 토큰, 최종 업무 상태).
- 시나리오 DB는 loopback 폐기용 DB여야 한다(suite는 `flyway.clean()`을 실행한다).
- 커밋 메시지/PR 본문/문서 산문은 `prose` 서브에이전트를 통해 한국어로 작성한다
  (AGENTS.md § Prose).

## 검토 포인트

1. 시나리오가 도중에 실패하거나 타임아웃되면 runner 자식 프로세스를 반드시 종료해야
   한다(그렇지 않으면 다음 시나리오의 클레임을 빼앗기고 포트가 누수된다). Task 3에서
   검증한다.
2. `sitTest`를 폐기용 DB가 아닌 곳에서 실행하면 `flyway.clean()` 전에 거부해야 한다.
   Task 1에서 검증한다.
3. `uatTest`를 로그인 볼륨/이미지/모델 없이 실행하면 모든 UAT 시나리오가 누락 항목
   명시와 함께 건너뜀으로 보고되어야 하며, 모델 호출이 0이어야 한다. Task 5에서 검증한다.
4. 에이전트가 기대 상태에 도달하지 못하면 타임아웃 내에 실패하고, 기대 상태와 마지막
   실패 코드를 메시지에 남기며, 멈추지 않아야 한다. Task 3에서 검증한다.
5. `@pending` 스크립트는 `sitTest`/`uatTest`에서 절대 실행되지 않아야 하고,
   `pendingScenarios`는 전부 나열해야 한다. Task 6에서 검증한다.

---

### Task 1: Cucumber 연결, 폐기용 DB 보호, 픽스처 Given

**파일:**
- 수정: `backend/build.gradle`
- 생성: `backend/src/test/java/com/mulinocoreano/backend/scenario/ScenarioSuite.java`
- 생성: `backend/src/test/java/com/mulinocoreano/backend/scenario/ScenarioContext.java`
- 생성: `backend/src/test/java/com/mulinocoreano/backend/scenario/ScenarioGuard.java`
- 생성: `backend/src/test/java/com/mulinocoreano/backend/scenario/ScenarioGuardTest.java`
- 생성: `backend/src/test/java/com/mulinocoreano/backend/scenario/FixtureSteps.java`
- 생성: `backend/src/test/resources/scenarios/mrp-p2p.feature`

**인터페이스:**
- 산출: `ScenarioGuard.requireDisposable(String dbUrl)`(`IllegalStateException` 발생);
  `@LocalServerPort`와 고정 `planningClock`을 가진 Spring context; `FixtureSteps`는
  시나리오마다 스키마를 초기화하고 `ReplenishmentDemoFixture`를 로드한다;
  태그/시스템 프로퍼티 계약 `mulino.scenario.agent` = `scripted` | `live`.

- [ ] **Step 1: 실패하는 guard 테스트 작성**

```java
package com.mulinocoreano.backend.scenario;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

/** 목표 외 안전장치: 시나리오는 스키마를 지우므로 폐기용 loopback DB에서만 돈다. */
class ScenarioGuardTest {
    @Test
    void onlyLoopbackScenarioDatabasesMayBeWiped() {
        assertThatCode(() -> ScenarioGuard.requireDisposable("jdbc:postgresql://localhost:55432/mulino_scenario"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ScenarioGuard.requireDisposable("jdbc:postgresql://db.example:5432/mulino_scenario"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ScenarioGuard.requireDisposable("jdbc:postgresql://localhost:5432/mulino_coreano"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ScenarioGuard.requireDisposable(null)).isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: 실행해 실패를 확인한다**

실행: `cd backend && ./gradlew test --tests '*ScenarioGuardTest'`
기대: 컴파일 FAIL (`ScenarioGuard` 미존재).

- [ ] **Step 3: guard 구현**

```java
package com.mulinocoreano.backend.scenario;

/** 시나리오는 flyway.clean()을 호출한다. 이름이 _scenario로 끝나는 loopback DB만 허용한다. */
public final class ScenarioGuard {
    private ScenarioGuard() {}

    public static void requireDisposable(String dbUrl) {
        if (dbUrl == null || !dbUrl.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[A-Za-z0-9_]+_scenario")) {
            throw new IllegalStateException("Scenario tests wipe their schema: set DB_URL to a loopback database named *_scenario");
        }
    }
}
```

- [ ] **Step 4: `backend/build.gradle`에 의존성과 태스크 추가**

`dependencies { ... }` 안에 추가:

```groovy
    testImplementation platform('io.cucumber:cucumber-bom:8.0.1')
    testImplementation 'io.cucumber:cucumber-java'
    testImplementation 'io.cucumber:cucumber-spring'
    testImplementation 'io.cucumber:cucumber-junit-platform-engine'
    testImplementation 'org.junit.platform:junit-platform-suite'
```

`test` 태스크를 수정해 시나리오를 제외하고 시나리오 태스크를 추가한다(`demo-e2e`
제외는 Task 7에서 삭제할 때까지 유지한다):

```groovy
tasks.named('test') {
    useJUnitPlatform { excludeTags 'demo-e2e', 'scenario' }
}

def scenarioTask = { String taskName, String tags, String agent, boolean dryRun ->
    tasks.register(taskName, Test) {
        group = 'verification'
        description = "Cucumber scenarios: ${tags}"
        testClassesDirs = sourceSets.test.output.classesDirs
        classpath = sourceSets.test.runtimeClasspath
        useJUnitPlatform { includeTags 'scenario' }
        systemProperty 'cucumber.filter.tags', tags
        systemProperty 'mulino.scenario.agent', agent
        if (dryRun) systemProperty 'cucumber.execution.dry-run', 'true'
        outputs.upToDateWhen { false }
        testLogging { events 'passed', 'failed', 'skipped'; showStandardStreams = true }
    }
}
scenarioTask('sitTest', '@sit and not @pending', 'scripted', false)
scenarioTask('uatTest', '@uat and not @pending', 'live', false)
scenarioTask('pendingScenarios', '@pending', 'scripted', true)
```

- [ ] **Step 5: Suite와 Spring context**

```java
package com.mulinocoreano.backend.scenario;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;
import org.junit.jupiter.api.Tag;
import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

@Suite
@Tag("scenario")
@IncludeEngines("cucumber")
@SelectClasspathResource("scenarios")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.mulinocoreano.backend.scenario")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty, summary")
public class ScenarioSuite {}
```

```java
package com.mulinocoreano.backend.scenario;

import io.cucumber.spring.CucumberContextConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.schemas=scenario", "spring.flyway.clean-disabled=false",
        "spring.flyway.init-sqls=CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public",
        "spring.datasource.hikari.schema=scenario", "spring.main.allow-bean-definition-overriding=true",
        "mulino.local-auth.worker-token=" + ScenarioContext.WORKER_TOKEN})
@Import(ScenarioContext.FixedClock.class)
public class ScenarioContext {
    public static final String WORKER_TOKEN = "scenario-worker-token";
    public static final Instant BUSINESS_NOW = Instant.parse("2026-09-05T00:00:00Z");

    static boolean live() { return "live".equals(System.getProperty("mulino.scenario.agent")); }

    @DynamicPropertySource
    static void runtime(DynamicPropertyRegistry r) {
        // 서버가 예약하는 Run의 런타임은 UAT 실행기와 같아야 한다. SIT 스크립트 실행기는 기본값 CODEX로 claim한다.
        r.add("mulino.execution.runtime", () -> live() ? System.getenv().getOrDefault("MULINO_AGENT_RUNTIME", "CODEX") : "CODEX");
    }

    @TestConfiguration
    static class FixedClock {
        @Bean("planningClock") @Primary
        Clock planningClock() { return Clock.fixed(BUSINESS_NOW, ZoneId.of("Asia/Seoul")); }
    }
}
```

- [ ] **Step 6: 첫 번째 스크립트(Given만 구현된 상태)**

`backend/src/test/resources/scenarios/mrp-p2p.feature`:

```gherkin
# language: ko
@MM @PP
기능: 재보충(MRP) → 구매(P2P)
  목표 2(승인 없는 쓰기 차단)·3(소요량 계산)·4(업무 연속)·5(에이전트 역할).
  픽스처 기준일 2026-09-05. 손계산: AMR 수요 60 + 안전 14 − 가용 30, 구매 후보 합계 16,500원.

  배경:
    조건 DEMO-AMR 가용 재고 30으로는 30일 수요 60과 안전재고 14를 채우지 못한다

  @TC-P2P-001 @sit @uat
  시나리오: MANAGER 승인 후에만 발주가 생성된다
    만일 OPERATOR가 DEMO-AMR·DEMO-BSC 재보충 목표를 접수한다
    그리고 에이전트가 소요량 계획과 구매 제안을 작성한다
    그러면 구매 제안은 승인 대기 상태다
    그리고 구매 제안 합계는 16500원이다
    그리고 발주는 생성되지 않았다
    만일 MANAGER가 구매 제안을 승인한다
    그러면 발주 금액 합계는 16500원이다
    만일 에이전트가 발주 이후 업무를 정리한다
    그러면 Case는 입고 확인을 기다린다
```

- [ ] **Step 7: 픽스처 스텝(초기화 + Given)**

`FixtureSteps.java`의 `@Given` 구현에서, 컬럼명이 다를 경우
`database/ddl/03_transaction_tables.sql`에서 `production_lots`를 확인하고 실제
컬럼명을 사용한다 — 업무 의미인 "활성·미만료 AMR LOT 잔량"은 동일하다.

```java
package com.mulinocoreano.backend.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import com.mulinocoreano.backend.planning.ReplenishmentDemoFixture;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import java.math.BigDecimal;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

public class FixtureSteps {
    @Autowired Flyway flyway;
    @Autowired JdbcClient jdbc;

    @Before(order = 0)
    public void resetToFixture() throws Exception {
        ScenarioGuard.requireDisposable(System.getenv("DB_URL"));
        flyway.clean();
        flyway.migrate();
        ReplenishmentDemoFixture.load(jdbc);
    }

    @Given("DEMO-AMR 가용 재고 {int}으로는 {int}일 수요 {int}과 안전재고 {int}를 채우지 못한다")
    public void amrCannotCoverDemand(int available, int days, int demand, int safety) {
        BigDecimal usable = jdbc.sql("""
                SELECT coalesce(sum(l.remaining_quantity),0) FROM production_lots l JOIN products p USING(product_id)
                WHERE p.sku='DEMO-AMR' AND l.status='ACTIVE' AND l.expiry_date >= DATE '2026-09-05'
                """).query(BigDecimal.class).single();
        assertThat(usable).isEqualByComparingTo(BigDecimal.valueOf(available));
        assertThat(available).isLessThan(demand + safety);
    }
}
```

- [ ] **Step 8: 실행**

실행: `cd backend && ./gradlew test --tests '*ScenarioGuardTest'` → PASS.
실행: `DB_URL=jdbc:postgresql://localhost:55432/mulino_scenario DB_USERNAME=postgres DB_PASSWORD=test ./gradlew sitTest`
기대: FAIL — Background 스텝 통과, `만일 OPERATOR가 …`는 UNDEFINED(Cucumber는
undefined 스텝을 실패로 보고한다). `./gradlew test`는 여전히 0개의 Cucumber 시나리오를 실행한다.

- [ ] **Step 9: 커밋** (`test(scenario): Cucumber 시나리오 실행 기반과 픽스처 조건`, 메시지는 `prose` 경유)

---

### Task 2: 사람 채널(역할별 stdio MCP)

**파일:**
- 생성: `mcp-server/scripts/scenario/human.mjs`
- 생성: `backend/src/test/java/com/mulinocoreano/backend/scenario/HumanChannel.java`
- 생성: `backend/src/test/java/com/mulinocoreano/backend/scenario/ScenarioWorld.java`
- 생성: `backend/src/test/java/com/mulinocoreano/backend/scenario/HumanSteps.java`

**인터페이스:**
- 소비: Task 1 context (`@LocalServerPort`).
- 산출: `HumanChannel.call(String role, String tool, Map<String,Object> args) -> ToolResult(boolean isError, JsonNode content)`;
  `ScenarioWorld`(`@ScenarioScope`) 필드 `caseRef`, `lastDecision`(`ToolResult`), `apiBase()`.

- [ ] **Step 1: `human.mjs` — 역할로 실제 MCP 도구 하나 호출**

```js
// 시나리오의 사람 역할: 실제 stdio MCP 서버를 그 역할로 띄워 도구 하나를 호출하고 결과를 한 줄 JSON으로 낸다.
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const [apiBase, role, tool, args = "{}"] = process.argv.slice(2);
const client = new Client({ name: "scenario-human", version: "1" });
await client.connect(new StdioClientTransport({
  command: process.execPath,
  args: [fileURLToPath(new URL("../../src/index.js", import.meta.url))],
  env: { PATH: process.env.PATH, MULINO_LOCAL_ROLE: role, MULINO_API_BASE: apiBase },
}));
try {
  const r = await client.callTool({ name: tool, arguments: JSON.parse(args) });
  process.stdout.write(JSON.stringify({ isError: Boolean(r.isError), content: r.structuredContent ?? null }) + "\n");
} finally {
  await client.close();
}
```

- [ ] **Step 2: `HumanChannel`과 `ScenarioWorld`**

```java
package com.mulinocoreano.backend.scenario;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 사람은 실제 사용자와 같은 경로(역할별 stdio MCP)로만 행동한다. */
public final class HumanChannel {
    public record ToolResult(boolean isError, JsonNode content) {}

    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private final ObjectMapper mapper;
    private final String apiBase;

    public HumanChannel(ObjectMapper mapper, String apiBase) { this.mapper = mapper; this.apiBase = apiBase; }

    public ToolResult call(String role, String tool, Map<String, Object> args) {
        try {
            var pb = new ProcessBuilder("node", ROOT.resolve("mcp-server/scripts/scenario/human.mjs").toString(),
                    apiBase, role, tool, mapper.writeValueAsString(args)).redirectErrorStream(false);
            pb.environment().keySet().retainAll(java.util.Set.of("PATH", "HOME"));
            Process p = pb.start();
            if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new AssertionError(role + " " + tool + " timed out"); }
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (p.exitValue() != 0 || out.isEmpty()) throw new AssertionError(role + " " + tool + " failed to run");
            JsonNode line = mapper.readTree(out.substring(out.lastIndexOf('\n') + 1));
            return new ToolResult(line.path("isError").asBoolean(), line.path("content"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}
```

```java
package com.mulinocoreano.backend.scenario;

import io.cucumber.spring.ScenarioScope;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.stereotype.Component;

@Component
@ScenarioScope
public class ScenarioWorld {
    @LocalServerPort int port;
    String caseRef;
    HumanChannel.ToolResult lastDecision;

    String apiBase() { return "http://127.0.0.1:" + port + "/api/v1"; }
}
```

(`ScenarioWorld`는 Task 1의 `@SpringBootTest`가 `com.mulinocoreano.backend`를
스캔하므로 자동으로 감지된다. 감지되지 않는다면 `ScenarioContext`에
`@ComponentScan(basePackageClasses = ScenarioWorld.class)`를 추가한다.)

- [ ] **Step 3: 사람 스텝**

```java
package com.mulinocoreano.backend.scenario;

import io.cucumber.java.en.When;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class HumanSteps {
    @Autowired ScenarioWorld world;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;

    private HumanChannel channel() { return new HumanChannel(mapper, world.apiBase()); }

    @When("OPERATOR가 DEMO-AMR·DEMO-BSC 재보충 목표를 접수한다")
    public void operatorOpensReplenishment() {
        long warehouse = jdbc.sql("SELECT warehouse_id FROM warehouses WHERE plant_id='DEMO-KR-01'").query(Long.class).single();
        var r = channel().call("OPERATOR", "create_case", Map.of(
                "objective", "DEMO-AMR·DEMO-BSC 재보충",
                "requestKey", "scenario-case",
                "replenishment", Map.of("productSkus", List.of("DEMO-AMR", "DEMO-BSC"),
                        "warehouseId", warehouse, "targetDate", "2026-10-04")));
        if (r.isError()) throw new AssertionError("Case intake rejected");
        world.caseRef = r.content().path("caseRef").asText();
    }

    @When("MANAGER가 구매 제안을 승인한다")
    public void managerApproves() { world.lastDecision = decide("MANAGER", "APPROVE", "scenario-approve"); }

    @When("MANAGER가 구매 제안을 반려한다")
    public void managerBlocks() { world.lastDecision = decide("MANAGER", "BLOCK", "scenario-block"); }

    @When("OPERATOR가 구매 제안 승인을 시도한다")
    public void operatorTriesToApprove() { world.lastDecision = decide("OPERATOR", "APPROVE", "scenario-operator"); }

    @When("MANAGER가 기존 구매 제안 승인을 시도한다")
    public void managerTriesStaleApproval() { world.lastDecision = decide("MANAGER", "APPROVE", "scenario-stale"); }

    /** 가장 최근 구매 제안을 현재 버전·해시로 결정한다. 거절 여부는 업무 상태로 확인한다. */
    HumanChannel.ToolResult decide(String role, String decision, String requestKey) {
        JsonNode approval = latestApproval("MANAGER");
        return channel().call(role, "decide_purchase", Map.of(
                "approvalId", approval.path("id").asLong(),
                "decision", decision,
                "expectedVersion", approval.path("version").asInt(),
                "proposalHash", approval.path("proposalHash").asText(),
                "reason", "scenario " + decision,
                "requestKey", requestKey));
    }

    JsonNode latestApproval(String role) {
        JsonNode view = channel().call(role, "get_case", Map.of("caseRef", world.caseRef)).content();
        JsonNode approvals = view.path("approvals");
        long id = approvals.get(approvals.size() - 1).path("governanceActionId").asLong();
        return channel().call(role, "get_approval", Map.of("approvalId", id)).content();
    }
}
```

(필드명 `approvals[].governanceActionId`, `id`, `version`, `proposalHash`는 같은
도구에 대해 기존 `scenario.mjs`가 사용하던 것이다 — 그대로 유지한다.)

- [ ] **Step 4: 실행** `./gradlew sitTest` → `그리고 에이전트가 …`에서 FAIL(undefined).
  접수 스텝은 통과한다(OPERATOR로 MCP를 통해 Case 생성).

- [ ] **Step 5: 커밋** (`test(scenario): 역할별 MCP 사람 채널`)

---

### Task 3: 에이전트 드라이버(SIT용 스크립트 runner), 대기, 정리

**파일:**
- 이동: `mcp-server/scripts/demo-e2e/model.mjs` → `agents/runner/scripts/scripted-agent.mjs` (`git mv`; 내용 그대로)
- 생성: `agents/runner/scripts/scripted-runner.mjs`
- 생성: `backend/src/test/java/com/mulinocoreano/backend/scenario/AgentDriver.java`
- 생성: `backend/src/test/java/com/mulinocoreano/backend/scenario/AgentDriverTest.java`
- 생성: `backend/src/test/java/com/mulinocoreano/backend/scenario/AgentSteps.java`

**인터페이스:**
- 소비: `ScenarioWorld.apiBase()`, `ScenarioContext.WORKER_TOKEN`, `ScenarioContext.live()`.
- 산출: `AgentDriver.start(Map<String,String> env)`, `awaitState(String expected, BooleanSupplier reached, Duration timeout)`,
  `restart()`, `stop()`, `List<JsonNode> modelFinished()`;
  `AgentSteps`는 시나리오당 드라이버 하나를 보유하고 `@After`에서 종료한다.

- [ ] **Step 1: 실패하는 드라이버 테스트(타임아웃 시 상태 명시; 프로세스 종료)**

```java
package com.mulinocoreano.backend.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 목표 4 보조: 에이전트가 기대 상태에 못 가면 멈추지 않고 이유와 함께 실패하며, 실행기는 반드시 정리된다. */
class AgentDriverTest {
    @Test
    void unreachedStateFailsWithinTheTimeoutAndTheChildIsKilled() {
        var driver = new AgentDriver(new ObjectMapper(),
                List.of("node", "-e", "console.log(JSON.stringify({event:'model_finished',failure:'MODEL_OUTPUT_TOO_LARGE'})); setInterval(()=>{},1000)"));
        driver.start(Map.of());
        assertThatThrownBy(() -> driver.awaitState("구매 제안 승인 대기", () -> false, Duration.ofMillis(800)))
                .hasMessageContaining("구매 제안 승인 대기").hasMessageContaining("MODEL_OUTPUT_TOO_LARGE");
        driver.stop();
        assertThat(driver.isAlive()).isFalse();
    }
}
```

(메시지 단언은 의도적이다. 이 테스트는 업무 문자열이 아니라 테스트 인프라의
진단 계약을 증명한다.)

- [ ] **Step 2: 실행** `./gradlew test --tests '*AgentDriverTest'` → 컴파일 FAIL.

- [ ] **Step 3: `scripted-runner.mjs`**

```js
// SIT 전용 실행기: 실제 Runner·WorkerApi에 스크립트 에이전트를 끼운다. 모델·하네스는 쓰지 않는다.
import { fileURLToPath } from "node:url";
import { Runner } from "../src/runner.js";
import { ProcessExecutor } from "../src/executor.js";
import { staticToken, WorkerApi } from "../src/http.js";

const agent = fileURLToPath(new URL("./scripted-agent.mjs", import.meta.url));
const env = process.env;
const executor = new ProcessExecutor({ invocation: claim => ({
  command: process.execPath, args: [agent],
  env: { PATH: env.PATH, MULINO_TOKEN: claim.capabilityToken, MULINO_API_URL: env.MULINO_API_BASE,
    DEMO_CLI: env.DEMO_CLI, DEMO_PLAN_INPUT: env.DEMO_PLAN_INPUT },
}) });
const runner = new Runner({
  api: new WorkerApi({ baseUrl: env.MULINO_API_BASE, tokenClient: staticToken(env.MULINO_WORKER_TOKEN) }),
  executor, workerId: env.MULINO_WORKER_ID ?? "scenario-scripted", pollMs: 200, maxRunMs: 30000,
  logger: event => process.stdout.write(`${JSON.stringify(event)}\n`),
});
process.once("SIGTERM", () => { void runner.stop(); });
await runner.loop();
```

- [ ] **Step 4: `AgentDriver`**

```java
package com.mulinocoreano.backend.scenario;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 실행기 프로세스 하나를 시나리오 동안 돌리고, 업무 상태 도달을 기다리며, 끝나면 반드시 종료한다. */
public final class AgentDriver {
    private final ObjectMapper mapper;
    private final List<String> command;
    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
    private Map<String, String> env = Map.of();
    private Process process;

    public AgentDriver(ObjectMapper mapper, List<String> command) { this.mapper = mapper; this.command = command; }

    public void start(Map<String, String> env) {
        this.env = env;
        try {
            var pb = new ProcessBuilder(command).redirectErrorStream(true);
            pb.environment().keySet().retainAll(java.util.Set.of("PATH", "HOME"));
            pb.environment().putAll(env);
            process = pb.start();
            Thread reader = new Thread(() -> {
                try (var in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    for (String l; (l = in.readLine()) != null; ) lines.add(l);
                } catch (java.io.IOException ignored) { }
            });
            reader.setDaemon(true);
            reader.start();
        } catch (java.io.IOException e) { throw new AssertionError("runner failed to start", e); }
    }

    public void awaitState(String expected, BooleanSupplier reached, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (reached.getAsBoolean()) return;
            try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
        List<String> codes = modelFinished().stream().map(e -> e.path("failure").asText("")).filter(s -> !s.isEmpty()).toList();
        throw new AssertionError("Agent did not reach: " + expected + " within " + timeout + "; model failures: " + codes);
    }

    public List<JsonNode> modelFinished() {
        List<JsonNode> out = new ArrayList<>();
        synchronized (lines) {
            for (String l : lines) {
                try { JsonNode n = mapper.readTree(l); if ("model_finished".equals(n.path("event").asText())) out.add(n); }
                catch (RuntimeException notJson) { }
            }
        }
        return out;
    }

    public void restart() { stop(); start(env); }

    public boolean isAlive() { return process != null && process.isAlive(); }

    public void stop() {
        if (process == null) return;
        process.destroy();
        try { if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
    }
}
```

- [ ] **Step 5: `AgentSteps`(SIT 모드; UAT 브랜치는 Task 5에서 추가)**

```java
package com.mulinocoreano.backend.scenario;

import io.cucumber.java.After;
import io.cucumber.java.en.When;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

public class AgentSteps {
    @Autowired ScenarioWorld world;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired BusinessState state;
    AgentDriver driver;

    Duration timeout() { return ScenarioContext.live() ? Duration.ofMinutes(15) : Duration.ofSeconds(60); }

    AgentDriver driver() {
        if (driver == null) {
            driver = new AgentDriver(mapper, List.of("node", HumanChannel.ROOT.resolve("agents/runner/scripts/scripted-runner.mjs").toString()));
            driver.start(scriptedEnv());
        }
        return driver;
    }

    Map<String, String> scriptedEnv() {
        long warehouse = jdbc.sql("SELECT warehouse_id FROM warehouses WHERE plant_id='DEMO-KR-01'").query(Long.class).single();
        List<Long> products = jdbc.sql("SELECT product_id FROM products WHERE sku IN ('DEMO-AMR','DEMO-BSC') ORDER BY product_id").query(Long.class).list();
        return Map.of("MULINO_API_BASE", world.apiBase(), "MULINO_WORKER_TOKEN", ScenarioContext.WORKER_TOKEN,
                "DEMO_CLI", HumanChannel.ROOT.resolve("agents/cli/zig-out/bin/mulino").toString(),
                "DEMO_PLAN_INPUT", "{\"warehouseId\":" + warehouse + ",\"productIds\":" + products + "}");
    }

    @When("에이전트가 소요량 계획과 구매 제안을 작성한다")
    public void agentPlansAndProposes() {
        driver().awaitState("구매 제안 승인 대기", () -> state.pendingApprovals(world.caseRef) >= 1, timeout());
    }

    @When("에이전트가 발주 이후 업무를 정리한다")
    public void agentHandlesAfterPurchase() {
        driver().awaitState("입고 확인 후속 업무", () -> state.followups(world.caseRef) == 1, timeout());
    }

    @When("에이전트가 반려를 확인한다")
    public void agentSeesBlock() {
        driver().awaitState("반려 후 에이전트 중단", () -> state.abortedOrchestratorRuns(world.caseRef) >= 1, timeout());
    }

    @After
    public void stopAgent() { if (driver != null) driver.stop(); }
}
```

- [ ] **Step 6: 실행** `./gradlew test --tests '*AgentDriverTest'` → PASS.
  `sitTest`는 Task 4에서 `BusinessState`가 추가될 때까지 계속 실패한다.

- [ ] **Step 7: 커밋** (`test(scenario): 실행기 구동과 업무 상태 대기`)

---

### Task 4: 업무 상태 단언과 MRP → P2P 테스트 케이스

**파일:**
- 생성: `backend/src/test/java/com/mulinocoreano/backend/scenario/BusinessState.java`
- 생성: `backend/src/test/java/com/mulinocoreano/backend/scenario/StateSteps.java`
- 생성: `backend/src/test/java/com/mulinocoreano/backend/scenario/WorldSteps.java`
- 수정: `backend/src/test/resources/scenarios/mrp-p2p.feature`

**인터페이스:**
- 산출: `BusinessState`(Spring `@Component`) — `pendingApprovals(caseRef)`,
  `latestApprovalStatus(caseRef)`, `appliedPurchaseOrders()`, `appliedPurchaseTotal()`,
  `followups(caseRef)`, `caseStatus(caseRef)`, `abortedOrchestratorRuns(caseRef)`,
  `completedSupplyRuns(caseRef)`, `openAttentionWithoutApproval(caseRef)`.

- [ ] **Step 1: 스크립트 완성(스텝이 없으므로 모든 케이스 실패)**

`mrp-p2p.feature`에 추가:

```gherkin
  @TC-P2P-002 @sit @uat
  시나리오: MANAGER가 반려하면 발주도 후속 업무도 생기지 않는다
    만일 OPERATOR가 DEMO-AMR·DEMO-BSC 재보충 목표를 접수한다
    그리고 에이전트가 소요량 계획과 구매 제안을 작성한다
    만일 MANAGER가 구매 제안을 반려한다
    그러면 구매 제안은 반려 상태다
    그리고 발주는 생성되지 않았다
    만일 에이전트가 반려를 확인한다
    그러면 후속 업무는 생성되지 않았다

  @TC-P2P-003 @sit
  시나리오: MANAGER가 아닌 역할은 구매를 승인할 수 없다
    만일 OPERATOR가 DEMO-AMR·DEMO-BSC 재보충 목표를 접수한다
    그리고 에이전트가 소요량 계획과 구매 제안을 작성한다
    만일 OPERATOR가 구매 제안 승인을 시도한다
    그러면 구매 제안은 승인 대기 상태다
    그리고 발주는 생성되지 않았다

  @TC-P2P-004 @sit @uat
  시나리오: 승인 대기 중 공급 단가가 바뀌면 옛 제안으로는 발주할 수 없고 재계산 후 다시 승인받는다
    만일 OPERATOR가 DEMO-AMR·DEMO-BSC 재보충 목표를 접수한다
    그리고 에이전트가 소요량 계획과 구매 제안을 작성한다
    만일 공급사가 DEMO 밀가루 단가를 1200원에서 1300원으로 올린다
    그리고 MANAGER가 기존 구매 제안 승인을 시도한다
    그러면 구매 제안은 만료 상태다
    그리고 발주는 생성되지 않았다
    만일 에이전트가 만료를 확인하고 사람에게 묻는다
    그리고 MANAGER가 변경된 단가로 재계산을 지시한다
    그리고 에이전트가 소요량 계획과 구매 제안을 작성한다
    그러면 구매 제안 합계는 17000원이다
    만일 MANAGER가 구매 제안을 승인한다
    그러면 발주 금액 합계는 17000원이다

  @TC-P2P-005 @sit
  시나리오: 실행기가 중간에 재시작되어도 계획을 다시 계산하지 않고 이어진다
    만일 OPERATOR가 DEMO-AMR·DEMO-BSC 재보충 목표를 접수한다
    그리고 에이전트 실행기가 소요량 계획 직후 재시작된다
    그리고 에이전트가 소요량 계획과 구매 제안을 작성한다
    그러면 구매 제안은 승인 대기 상태다
    그리고 소요량 계획은 한 번만 계산되었다
```

(17,000원 손계산: 밀가루 5 KG × 1,300 = 6,500 + 설탕 7,500 + 포장 3,000 —
기존 데모 테스트의 `5:1300`, `5:1500`, `60:50`과 같은 근거.)

- [ ] **Step 2: 실행** `./gradlew sitTest` → `그러면 …` 스텝이 undefined로 FAIL.

- [ ] **Step 3: `BusinessState`**

(실행 전 `governance_actions.case_id`와 `attention_requests.governance_action_id`
존재 여부를 확인한다: `grep -n "CREATE TABLE governance_actions" -A15 database/ddl/*.sql`
및 `grep -n "governance_action_id" database/ddl/16_attention_answer.sql`. 만약
`governance_actions`가 `case_id` 대신 `resource_id`로 플랜에 연결된다면
`replenishment_plans`를 `resource_id`로 조인한다 — 업무 의미인 "이 Case 플랜의
승인"은 동일하다.)

```java
package com.mulinocoreano.backend.scenario;

import java.math.BigDecimal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 시나리오의 '그러면'이 보는 ERP 업무 상태. 응답 문구나 필드 존재는 보지 않는다. */
@Component
public class BusinessState {
    private final JdbcClient jdbc;
    public BusinessState(JdbcClient jdbc) { this.jdbc = jdbc; }

    long pendingApprovals(String caseRef) {
        return count("SELECT count(*) FROM governance_actions ga JOIN cases c ON c.case_id=ga.case_id WHERE c.case_ref=:c AND ga.status='PENDING'", caseRef);
    }
    String latestApprovalStatus(String caseRef) {
        return jdbc.sql("SELECT ga.status::text FROM governance_actions ga JOIN cases c ON c.case_id=ga.case_id WHERE c.case_ref=:c ORDER BY ga.governance_action_id DESC LIMIT 1")
                .param("c", caseRef).query(String.class).single();
    }
    long appliedPurchaseOrders() {
        return jdbc.sql("SELECT count(*) FROM purchase_orders WHERE purchase_application_id IS NOT NULL").query(Long.class).single();
    }
    BigDecimal appliedPurchaseTotal() {
        return jdbc.sql("SELECT coalesce(sum(i.quantity*i.unit_price),0) FROM purchase_order_items i JOIN purchase_orders p USING(purchase_order_id) WHERE p.purchase_application_id IS NOT NULL")
                .query(BigDecimal.class).single();
    }
    long followups(String caseRef) {
        return count("SELECT count(*) FROM replenishment_followups f JOIN cases c ON c.case_id=f.case_id WHERE c.case_ref=:c", caseRef);
    }
    String caseStatus(String caseRef) {
        return jdbc.sql("SELECT status::text FROM cases WHERE case_ref=:c").param("c", caseRef).query(String.class).single();
    }
    long abortedOrchestratorRuns(String caseRef) {
        return count("SELECT count(*) FROM runs r JOIN agents a USING(agent_id) JOIN cases c ON c.case_id=r.case_id WHERE c.case_ref=:c AND a.agent_key='ORCHESTRATOR' AND r.outcome='ABORTED'", caseRef);
    }
    long completedSupplyRuns(String caseRef) {
        return count("SELECT count(*) FROM runs r JOIN agents a USING(agent_id) JOIN cases c ON c.case_id=r.case_id WHERE c.case_ref=:c AND a.agent_key='SUPPLY_CHAIN' AND r.outcome='DONE'", caseRef);
    }
    long plans(String caseRef) {
        return count("SELECT count(*) FROM replenishment_plans p JOIN cases c ON c.case_id=p.case_id WHERE c.case_ref=:c", caseRef);
    }
    long openAttentionWithoutApproval(String caseRef) {
        return count("SELECT count(*) FROM attention_requests a JOIN cases c ON c.case_id=a.case_id WHERE c.case_ref=:c AND a.status='OPEN' AND a.governance_action_id IS NULL", caseRef);
    }
    private long count(String sql, String caseRef) { return jdbc.sql(sql).param("c", caseRef).query(Long.class).single(); }
}
```

- [ ] **Step 4: `StateSteps`와 `WorldSteps`**

```java
package com.mulinocoreano.backend.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import io.cucumber.java.en.Then;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

public class StateSteps {
    @Autowired ScenarioWorld world;
    @Autowired BusinessState state;
    @Autowired ObjectMapper mapper;

    @Then("구매 제안은 승인 대기 상태다")
    public void pending() { assertThat(state.latestApprovalStatus(world.caseRef)).isEqualTo("PENDING"); }

    @Then("구매 제안은 반려 상태다")
    public void blocked() { assertThat(state.latestApprovalStatus(world.caseRef)).isEqualTo("BLOCKED"); }

    @Then("구매 제안은 만료 상태다")
    public void expired() { assertThat(state.latestApprovalStatus(world.caseRef)).isEqualTo("EXPIRED"); }

    @Then("구매 제안 합계는 {long}원이다")
    public void proposalTotal(long krw) {
        var approval = new HumanSteps().withContext(world, mapper).latestApproval("MANAGER");
        assertThat(new BigDecimal(approval.path("proposal").path("totalKrw").asText())).isEqualByComparingTo(BigDecimal.valueOf(krw));
    }

    @Then("발주는 생성되지 않았다")
    public void noPurchaseOrder() { assertThat(state.appliedPurchaseOrders()).isZero(); }

    @Then("발주 금액 합계는 {long}원이다")
    public void purchaseTotal(long krw) {
        assertThat(state.appliedPurchaseOrders()).isEqualTo(1);
        assertThat(state.appliedPurchaseTotal()).isEqualByComparingTo(BigDecimal.valueOf(krw));
    }

    @Then("Case는 입고 확인을 기다린다")
    public void caseWaitsForReceipt() {
        assertThat(state.caseStatus(world.caseRef)).isEqualTo("WAITING");
        assertThat(state.followups(world.caseRef)).isEqualTo(1);
    }

    @Then("후속 업무는 생성되지 않았다")
    public void noFollowup() { assertThat(state.followups(world.caseRef)).isZero(); }

    @Then("소요량 계획은 한 번만 계산되었다")
    public void planCalculatedOnce() {
        assertThat(state.plans(world.caseRef)).isEqualTo(1);
        assertThat(state.completedSupplyRuns(world.caseRef)).isEqualTo(1);
    }
}
```

다른 스텝 클래스가 MCP 코드를 중복하지 않고 현재 승인을 읽을 수 있도록
`HumanSteps`에 헬퍼 추가:

```java
    HumanSteps withContext(ScenarioWorld world, ObjectMapper mapper) { this.world = world; this.mapper = mapper; return this; }
```

```java
package com.mulinocoreano.backend.scenario;

import io.cucumber.java.en.When;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** 외부 세계의 변화(공급사 단가)와 여러 역할이 얽힌 단계. */
public class WorldSteps {
    @Autowired ScenarioWorld world;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired BusinessState state;
    @Autowired AgentSteps agent;

    @When("공급사가 DEMO 밀가루 단가를 {int}원에서 {int}원으로 올린다")
    public void supplierRaisesFlourPrice(int from, int to) {
        int updated = jdbc.sql("""
                UPDATE supplier_material_terms t SET unit_price=:to
                FROM raw_materials m WHERE m.raw_material_id=t.raw_material_id AND m.name='DEMO 밀가루' AND t.unit_price=:from
                """).param("to", to).param("from", from).update();
        if (updated != 1) throw new AssertionError("expected exactly one flour term at " + from);
    }

    @When("에이전트가 만료를 확인하고 사람에게 묻는다")
    public void agentAsksAfterExpiry() {
        agent.driver().awaitState("만료 후 사람 질의", () -> state.openAttentionWithoutApproval(world.caseRef) == 1, agent.timeout());
    }

    @When("MANAGER가 변경된 단가로 재계산을 지시한다")
    public void managerOrdersRecalculation() {
        var human = new HumanSteps().withContext(world, mapper);
        var view = new HumanChannel(mapper, world.apiBase()).call("MANAGER", "get_case", Map.of("caseRef", world.caseRef)).content();
        var attention = java.util.stream.StreamSupport.stream(view.path("attention").spliterator(), false)
                .filter(a -> "OPEN".equals(a.path("status").asText()) && a.path("governanceActionId").isMissingNode())
                .findFirst().orElseThrow();
        String oldPlan = human.latestApproval("MANAGER").path("planRef").asText();
        var r = new HumanChannel(mapper, world.apiBase()).call("MANAGER", "answer_attention", Map.of(
                "attentionRequestId", attention.path("attentionRequestId").asLong(),
                "expectedVersion", attention.path("version").asInt(),
                "scope", "THIS_CASE",
                "answer", "Recalculate this Case using the changed supplier price; request a fresh purchase approval. Source plan: " + oldPlan + ".",
                "requestKey", "scenario-recalculate"));
        if (r.isError()) throw new AssertionError("recalculation answer rejected");
    }

    @When("에이전트 실행기가 소요량 계획 직후 재시작된다")
    public void runnerRestartsAfterPlan() {
        agent.driver().awaitState("소요량 계획 저장", () -> state.plans(world.caseRef) == 1, agent.timeout());
        agent.driver().restart();
    }
}
```

참고: `AgentSteps`는 `WorldSteps`에 주입 가능해야 한다. Cucumber-Spring은 시나리오
내에서 스텝 정의 인스턴스를 공유하므로 `@Autowired AgentSteps agent`는 동작한다
(스텝 클래스는 cucumber-spring 하에서 시나리오 스코프 빈이다). 답변 문자열은
스크립트 에이전트가 인식하는 정확한 지시문이다(`scripted-agent.mjs` 참조); 실제
모델은 이를 일반 지시로 읽는다.

- [ ] **Step 5: 실행** `./gradlew sitTest` → `@TC-P2P-00x` 5건 모두 PASS.
  사전 조건: 폐기용 `*_scenario` DB, Docker(jOOQ), Node 의존성(`cd mcp-server && npm ci`),
  `cd agents/cli && zig build`.

- [ ] **Step 6: 커밋** (`test(scenario): 재보충→구매 SIT 테스트 케이스 5건`)

---

### Task 5: UAT 모드 — 실제 하네스, 사전 조건, 증거

**파일:**
- 생성: `backend/src/test/java/com/mulinocoreano/backend/scenario/UatEvidence.java`
- 생성: `backend/src/test/java/com/mulinocoreano/backend/scenario/UatEvidenceTest.java`
- 수정: `backend/src/test/java/com/mulinocoreano/backend/scenario/AgentSteps.java`

**인터페이스:**
- 소비: `AgentDriver.modelFinished()`, `BusinessState`.
- 산출: `UatEvidence.prerequisitesMissing(Map<String,String> env) -> List<String>`;
  `UatEvidence.summarize(List<JsonNode> modelFinished) -> Map<String,Object>`
  (runs, failures, costUsd, inputTokens, outputTokens);
  `UatEvidence.write(Path dir, String tcId, Map<String,Object> record)`.

- [ ] **Step 1: 실패하는 테스트 — 비용 합산·실패 보존·누락 사전 조건 명시**

```java
package com.mulinocoreano.backend.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 목표 5 증거: UAT는 실행마다 비용·토큰·실패 코드를 합산해 남기고, 준비되지 않은 환경에서는 모델을 부르지 않는다. */
class UatEvidenceTest {
    ObjectMapper m = new ObjectMapper();

    @Test
    void summarisesCostTokensAndFailuresAcrossRuns() throws Exception {
        var s = UatEvidence.summarize(List.of(
                m.readTree("{\"event\":\"model_finished\",\"costUsd\":0.12,\"inputTokens\":100,\"outputTokens\":20}"),
                m.readTree("{\"event\":\"model_finished\",\"failure\":\"MODEL_OUTPUT_TOO_LARGE\",\"costUsd\":0.05}")));
        assertThat(s.get("runs")).isEqualTo(2);
        assertThat((double) s.get("costUsd")).isEqualTo(0.17, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(s.get("inputTokens")).isEqualTo(100L);
        assertThat(s.get("failures")).isEqualTo(List.of("MODEL_OUTPUT_TOO_LARGE"));
    }

    @Test
    void missingPrerequisitesAreNamedSoNoModelIsCalled() {
        assertThat(UatEvidence.prerequisitesMissing(Map.of("MULINO_AGENT_RUNTIME", "CLAUDE")))
                .containsExactlyInAnyOrder("MULINO_AGENT_MODEL", "MULINO_RUNTIME_IMAGE", "MULINO_AUTH_VOLUME");
    }
}
```

- [ ] **Step 2: 실행** `./gradlew test --tests '*UatEvidenceTest'` → 컴파일 FAIL.

- [ ] **Step 3: `UatEvidence`**

```java
package com.mulinocoreano.backend.scenario;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class UatEvidence {
    static final List<String> REQUIRED = List.of("MULINO_AGENT_RUNTIME", "MULINO_AGENT_MODEL", "MULINO_RUNTIME_IMAGE", "MULINO_AUTH_VOLUME");
    private UatEvidence() {}

    static List<String> prerequisitesMissing(Map<String, String> env) {
        return REQUIRED.stream().filter(k -> env.getOrDefault(k, "").isBlank()).toList();
    }

    static Map<String, Object> summarize(List<JsonNode> modelFinished) {
        double cost = 0; long in = 0, out = 0; List<String> failures = new ArrayList<>();
        for (JsonNode e : modelFinished) {
            cost += e.path("costUsd").asDouble(0);
            in += e.path("inputTokens").asLong(0);
            out += e.path("outputTokens").asLong(0);
            if (e.hasNonNull("failure")) failures.add(e.get("failure").asText());
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("runs", modelFinished.size()); s.put("costUsd", cost); s.put("inputTokens", in); s.put("outputTokens", out); s.put("failures", failures);
        return s;
    }

    static Path write(ObjectMapper mapper, Path dir, String tcId, Map<String, Object> record) throws java.io.IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(tcId + ".json");
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(record));
        return file;
    }
}
```

- [ ] **Step 4: `AgentSteps`에 UAT 브랜치 추가**

`driver()`를 교체하고 `@Before` 건너뜀과 `@After` 증거 기록을 추가한다:

```java
    @io.cucumber.java.Before(order = 1)
    public void skipUatWithoutPrerequisites() {
        if (!ScenarioContext.live()) return;
        var missing = UatEvidence.prerequisitesMissing(System.getenv());
        org.junit.jupiter.api.Assumptions.assumeTrue(missing.isEmpty(), "UAT skipped: prerequisite missing " + missing);
    }

    AgentDriver driver() {
        if (driver == null) {
            var env = System.getenv();
            driver = ScenarioContext.live()
                    ? new AgentDriver(mapper, List.of("node", HumanChannel.ROOT.resolve("agents/runner/src/main.js").toString()))
                    : new AgentDriver(mapper, List.of("node", HumanChannel.ROOT.resolve("agents/runner/scripts/scripted-runner.mjs").toString()));
            driver.start(ScenarioContext.live() ? liveEnv(env) : scriptedEnv());
        }
        return driver;
    }

    Map<String, String> liveEnv(Map<String, String> env) {
        var m = new java.util.HashMap<String, String>();
        m.put("MULINO_WORKER_TOKEN", ScenarioContext.WORKER_TOKEN);
        m.put("MULINO_WORKER_ID", "uat-" + world.port);
        m.put("MULINO_API_BASE", world.apiBase());
        m.put("MULINO_AGENT_API_URL", "http://host.docker.internal:" + world.port + "/api/v1");
        for (String k : List.of("MULINO_AGENT_RUNTIME", "MULINO_AGENT_MODEL", "MULINO_RUNTIME_IMAGE", "MULINO_AUTH_VOLUME", "DOCKER_HOST"))
            if (env.get(k) != null) m.put(k, env.get(k));
        return m;
    }

    @After(order = 10)
    public void recordUatEvidence(io.cucumber.java.Scenario scenario) throws Exception {
        if (!ScenarioContext.live() || driver == null) return;
        String tc = scenario.getSourceTagNames().stream().filter(t -> t.startsWith("@TC-")).findFirst().orElse("@TC-UNKNOWN").substring(1);
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("testCase", tc);
        record.put("status", scenario.getStatus().name());
        record.put("runtime", System.getenv("MULINO_AGENT_RUNTIME"));
        record.put("model", System.getenv("MULINO_AGENT_MODEL"));
        record.putAll(UatEvidence.summarize(driver.modelFinished()));
        record.put("appliedPurchaseOrders", state.appliedPurchaseOrders());
        record.put("appliedPurchaseTotalKrw", state.appliedPurchaseTotal());
        record.put("caseStatus", world.caseRef == null ? null : state.caseStatus(world.caseRef));
        Path file = UatEvidence.write(mapper, Path.of("build/uat", java.time.LocalDate.now().toString()), tc, record);
        scenario.log("UAT evidence: " + file);
    }
```

(`@After(order = 10)`은 기본 order 10000의 `stopAgent()` 전에 실행된다; Cucumber는
`@After`를 `order` 높은 순으로 실행한다.) `import java.nio.file.Path;`를 추가한다.

- [ ] **Step 5: 실행**
- `./gradlew test --tests '*UatEvidenceTest'` → PASS.
- `./gradlew uatTest`(UAT 환경 변수 없음) → 모든 UAT 케이스 SKIPPED,
  메시지에 누락 변수 명시, Docker 컨테이너 시작 없음.
- 선택 사항(비용 발생, 소유자에게 먼저 알릴 것):
  `MULINO_AGENT_RUNTIME=CLAUDE MULINO_AGENT_MODEL=claude-haiku-4-5 MULINO_RUNTIME_IMAGE=mulino-agent-runtime:codex-0.154.0-claude-2.1.282 MULINO_AUTH_VOLUME=mulino-claude-auth DOCKER_HOST=... ./gradlew uatTest --tests ScenarioSuite -Dcucumber.filter.tags='@TC-P2P-001'`
  → PASS, `build/uat/<date>/TC-P2P-001.json`에 `costUsd`가 0이 아닌 값으로 기록됨.

- [ ] **Step 6: 커밋** (`test(scenario): 실하네스 UAT 모드와 증거 기록`)

---

### Task 6: 시나리오 2·3 보류 스크립트

**파일:**
- 생성: `backend/src/test/resources/scenarios/qm-inbound-inspection.feature`
- 생성: `backend/src/test/resources/scenarios/batch-recall.feature`

- [ ] **Step 1: QM 스크립트**

```gherkin
# language: ko
@QM @MM @pending @issue-26
기능: 입고 품질 검사(QM) → 원재료 LOT 차단
  목표 1(LOT 추적)·2(승인 없는 쓰기 차단)·6(한국 규제).

  @TC-QM-001 @sit @uat
  시나리오: 온도 이상 입고는 원재료 LOT을 보류하고 QC 승인을 기다린다
    조건 냉장 원재료 입고의 기록 온도가 허용 범위를 벗어났다
    만일 에이전트가 입고 품질을 점검한다
    그러면 해당 원재료 LOT은 보류 제안 상태다
    그리고 해당 원재료 LOT은 생산에 투입할 수 없다

  @TC-QM-002 @sit
  시나리오: QC가 승인하면 차단이 확정된다
    조건 원재료 LOT 보류 제안이 QC 승인을 기다린다
    만일 QC가 보류 제안을 승인한다
    그러면 해당 원재료 LOT은 차단 상태다

  @TC-QM-003 @sit
  시나리오: QC가 아닌 역할의 승인은 아무것도 바꾸지 않는다
    조건 원재료 LOT 보류 제안이 QC 승인을 기다린다
    만일 MANAGER가 보류 제안 승인을 시도한다
    그러면 보류 제안은 승인 대기 상태다
    그리고 해당 원재료 LOT 상태는 바뀌지 않았다

  @TC-QM-004 @sit
  시나리오: 22개 알레르겐 매핑이 없는 원재료는 입고할 수 없다
    조건 알레르겐 매핑이 없는 원재료가 입고된다
    만일 에이전트가 입고 품질을 점검한다
    그러면 해당 입고는 차단 제안 상태다

  @TC-QM-005 @sit
  시나리오: 인증이 만료된 공급사의 입고는 차단된다
    조건 공급사의 HACCP 인증이 입고일 이전에 만료되었다
    만일 해당 공급사의 원재료가 입고된다
    그러면 해당 입고는 차단 제안 상태다
```

- [ ] **Step 2: 리콜 스크립트**

```gherkin
# language: ko
@QM @SD @pending @issue-27
기능: 배치 역추적과 리콜
  목표 1(LOT 추적)·2(승인 없는 쓰기 차단)·6(한국 규제: 식약처 보고, 기록 2년).

  @TC-RC-001 @sit @uat
  시나리오: 완제품 컴플레인으로 원재료까지 역추적하고 영향 고객을 찾는다
    조건 고객이 완제품 LOT에 대해 컴플레인을 접수했다
    만일 에이전트가 해당 LOT을 역추적한다
    그러면 원재료 LOT과 공급사까지 추적된다
    그리고 같은 원재료 LOT을 쓴 완제품의 출고 고객이 모두 식별된다

  @TC-RC-002 @sit
  시나리오: ADMIN 승인 전에는 리콜이 생성되지 않는다
    조건 에이전트가 리콜을 제안했다
    그러면 리콜은 생성되지 않았다
    그리고 대상 완제품 LOT 상태는 바뀌지 않았다

  @TC-RC-003 @sit
  시나리오: ADMIN이 승인하면 LOT이 회수 상태가 되고 식약처 보고 기록이 남는다
    조건 에이전트가 리콜을 제안했다
    만일 ADMIN이 리콜을 승인한다
    그러면 대상 완제품 LOT은 회수 상태다
    그리고 식약처 보고 기록이 생성되었다
```

- [ ] **Step 3: 보류 계약 검증**

실행: `./gradlew sitTest` → `@TC-P2P-*`만 실행됨(QM/RC 시나리오는 보고서에 없음).
실행: `./gradlew pendingScenarios` → dry-run이 8개 시나리오(`TC-QM-001..005`,
`TC-RC-001..003`)를 undefined 스텝과 함께 나열하고 백엔드 업무 흐름 없이 종료.

(dry-run에서 undefined 스텝이 빌드를 실패시키면, Cucumber 8에는
`cucumber.execution.strict=false`가 없으므로 태스크에 `ignoreFailures = true`를
추가하고 목록을 로그에서 확인한다. 어떤 것을 사용했는지 커밋 메시지에 기록한다.)

- [ ] **Step 4: 커밋** (`test(scenario): 품질·리콜 보류 테스트 스크립트`)

---

### Task 7: 기존 데모 인수 테스트 폐기

**파일:**
- 생성: `backend/src/test/java/com/mulinocoreano/backend/scenario/BackendRestartRecoveryTest.java`
- 삭제: `backend/src/test/java/com/mulinocoreano/backend/demo/DemoE2eTest.java`,
  `mcp-server/scripts/demo-e2e/scenario.mjs`, `mcp-server/scripts/demo-e2e/README.md`
- 수정: `backend/build.gradle`(`demoE2eTest` 태스크와 `demo-e2e` 제외 삭제)
- 수정: `demoE2eTest`를 언급하는 문서
  (`grep -rn demoE2eTest docs agents mcp-server backend AGENTS.md`) — 산문은 `prose` 경유

**인터페이스:**
- 소비: `HumanChannel`, `AgentDriver`, `BusinessState`(직접 인스턴스화; 이것은 Cucumber가 아닌 JUnit 테스트다).

- [ ] **Step 1: Cucumber로 표현할 수 없는 동작 보존 — 승인 대기 중 백엔드 재시작(목표 4)**

```java
package com.mulinocoreano.backend.scenario;

import static org.assertj.core.api.Assertions.assertThat;
// imports as in DemoE2eTest for SpringBootTest/DirtiesContext/Order/Tag

/** 목표 4: 승인 대기 중 백엔드가 재시작되어도 같은 제안을 승인하면 발주가 한 번 생기고 계획은 다시 계산되지 않는다. */
@Tag("scenario")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = { /* same as ScenarioContext */ })
@Import(ScenarioContext.FixedClock.class)
class BackendRestartRecoveryTest {
    // Order(1) + @DirtiesContext(AFTER_METHOD): reset fixture, OPERATOR create_case via HumanChannel,
    //   scripted AgentDriver until pendingApprovals==1, stop driver, remember caseRef in a static field.
    // Order(2): new context; assert pendingApprovals==1 still; MANAGER approve via HumanChannel;
    //   assert appliedPurchaseOrders==1, appliedPurchaseTotal==16500, plans==1.
}
```

두 메서드 전체를 삭제 대상인 `DemoE2eTest` 구조에 따라 작성한다
(`preparePendingApprovalThenStopTheRealApplicationContext` /
`restartTheRealApplicationAndResumePersistedApproval`). `launch(...scenario.mjs...)`
호출을 Task 2~3의 `HumanChannel`/`AgentDriver` 호출로 교체하고, SQL 단언을
`BusinessState`로 대체한다. `sitTest` 태스크는 `includeTags 'scenario'`로 이 테스트를
포함한다; `@BeforeAll`에서 `ScenarioGuard.requireDisposable`로 DB를 보호한다.

- [ ] **Step 2: 실행** `./gradlew sitTest` → Cucumber 케이스 5건 + 재시작 메서드 2건 PASS.

- [ ] **Step 3: 기존 테스트·스크립트 삭제, build.gradle·문서 수정**

실행 `./gradlew test sitTest` → PASS;
`grep -rn "demoE2eTest\|demo-e2e" --include=*.gradle --include=*.java --include=*.mjs .` → 결과 없음.

- [ ] **Step 4: 커밋** (`test(scenario): 데모 인수 테스트를 SIT로 이관하고 삭제`)

---

### Task 8: 기존 테스트 목록 작성(삭제 없음)

**파일:**
- 수정: `docs/superpowers/specs/2026-09-25-scenario-tests-design.md` §9
  (분류표, 산문은 `prose` 경유)

- [ ] **Step 1: 기준선 측정** — 새 DB 기준으로 `./gradlew test` wall time과 건수
  (backend 테스트 메서드, runner `npm test`, mcp `npm test`) 기록.
- [ ] **Step 2: 분류** — backend, runner, mcp-server의 모든 테스트 클래스를
  `testing` 스킬 §6 기준에 따라 유지/고쳐 쓰기/SIT 이관/삭제로 분류;
  클래스당 1행: 증명하는 목표, 분류 이유, 영향 메서드 수. 메시지 문구 단언
  (`grep -rn 'jsonPath("\$.message")\|hasMessage' backend/src/test`)은 고쳐 쓰기 후보.
- [ ] **Step 3: 소유자 검토** — 표를 보여주고 멈춘다. 삭제는 소유자 승인 후
  Task 9에서만 실행한다.
- [ ] **Step 4: 커밋** 분류표 (`docs: 기존 테스트 분류표`).

### Task 9: 승인된 정리 적용

- [ ] **Step 1:** 클래스 단위로 고쳐 쓰기(업무 상태 단언으로 교체)와 승인된
  삭제 적용; 클래스마다 `./gradlew test` 실행.
- [ ] **Step 2:** 수정 후 건수와 wall time을 §9 기준선 옆에 기록한다.
- [ ] **Step 3: 커밋** (`test: 목표에 연결되지 않는 테스트 정리`) 후 #57의 PR을
  연다(한국어 템플릿, `prose` 경유, `Closes #57`).

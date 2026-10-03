package com.mulinocoreano.backend.interfacepackage;

import com.mulinocoreano.backend.planning.ReplenishmentDemoFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 목표 4·5: 명시적 인간 보충 위임만 배포 런타임으로 실행을 예약한다. */
@SpringBootTest(properties={"spring.flyway.schemas=runtime_intake_it", "spring.datasource.hikari.schema=runtime_intake_it", "agent.runtime.default=CLAUDE"})
@AutoConfigureMockMvc @ActiveProfiles("local") @Transactional
class RuntimeIntakeIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired RunService runs;
    @Autowired CaseIntakeService intake;

    @Test void explicitHumanScopeQueuesConfiguredRuntimeAndReplaysWithoutErpWrites() throws Exception {
        ReplenishmentDemoFixture.load(jdbc);
        long purchaseCount=jdbc.sql("SELECT count(*) FROM purchase_orders").query(Long.class).single();
        long warehouse=jdbc.sql("SELECT warehouse_id FROM warehouses WHERE plant_id='DEMO-KR-01'").query(Long.class).single();
        String body=mapper.writeValueAsString(Map.of("objective","bounded replenishment","replenishment",Map.of("warehouseId",warehouse,"productSkus",List.of("demo-amr"))));
        String first=create(body,"runtime-scope");
        assertThat(create(body,"runtime-scope")).isEqualTo(first);
        jdbc.sql("UPDATE planning_policies SET horizon_days=horizon_days+1 WHERE warehouse_id=:id").param("id",warehouse).update();
        assertThat(create(body,"runtime-scope")).isEqualTo(first);
        long id=mapper.readTree(first).get("caseId").asLong();
        assertThat(runs.defaultRuntime()).isEqualTo("CLAUDE");
        assertThat(jdbc.sql("SELECT runtime FROM runs WHERE case_id=:id").param("id",id).query(String.class).single()).isEqualTo("CLAUDE");
        assertThat(jdbc.sql("SELECT metadata->'replenishment'->'productIds' = to_jsonb(ARRAY[product_id]) FROM cases,products WHERE case_id=:id AND sku='DEMO-AMR'").param("id",id).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM purchase_orders").query(Long.class).single()).isEqualTo(purchaseCount);
        mvc.perform(post("/api/v1/cases").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("SELECT count(*) FROM runs WHERE case_id=:id").param("id",id).query(Long.class).single()).isEqualTo(1);
    }
    @Test void anonymousThreeFieldIntakeIgnoresKeyAndNeverQueuesRuns() throws Exception {
        String body="{\"objective\":\"generic anonymous\",\"intentType\":\"ACT\",\"channel\":\"CHAT\"}";
        long first=anonymous(body); long second=anonymous(body);
        assertThat(first).isNotEqualTo(second);
        assertThat(jdbc.sql("SELECT count(*) FROM runs WHERE case_id IN (:a,:b)").param("a",first).param("b",second).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM cases WHERE case_id IN (:a,:b) AND opened_by_user_id IS NULL").param("a",first).param("b",second).query(Long.class).single()).isEqualTo(2);
    }
    @Test void genericHumanIntakeKeepsReadyResponsibilityWithoutModelDelegation() throws Exception {
        long id=mapper.readTree(create("{\"objective\":\"generic human\"}","generic-human")).get("caseId").asLong();
        assertThat(jdbc.sql("SELECT count(*) FROM runs WHERE case_id=:id").param("id",id).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT status::text FROM work_items WHERE case_id=:id").param("id",id).query(String.class).single()).isEqualTo("READY");
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> intake.createCase(new CreateCaseRequest("anonymous scope",null,null,
            new CreateCaseRequest.Replenishment(List.of("DEMO-AMR"),1L,null)),"ignored"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void malformedHumanScopeDoesNotCreateCaseOrRun() throws Exception {
        long before=jdbc.sql("SELECT count(*) FROM cases").query(Long.class).single();
        mvc.perform(post("/api/v1/cases").header("X-Mulino-Local-Role","MANAGER").contentType("application/json")
            .content("{\"objective\":\"invalid\",\"replenishment\":{\"productSkus\":[\"missing\"]}}")) .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM cases").query(Long.class).single()).isEqualTo(before);
    }
    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void activeWarehouseRejectsFreshIntakeAtomicallyAndKeepsOriginalReplay() throws Exception {
        String body = scopedBody("active warehouse conflict");
        try {
            String first = create(body, "active-conflict-original");
            var before = intakeCounts();
            mvc.perform(post("/api/v1/cases").header("X-Mulino-Local-Role", "MANAGER")
                    .header("Idempotency-Key", "active-conflict-second")
                    .contentType("application/json").content(body)).andExpect(status().isConflict());
            assertThat(intakeCounts()).isEqualTo(before);
            assertThat(create(body, "active-conflict-original")).isEqualTo(first);
            assertThat(intakeCounts()).isEqualTo(before);
        } finally {
            removeScopedIntakes("active warehouse conflict", "active-conflict-%");
        }
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void competingWarehouseIntakesCommitOnlyOneCaseAndRejectTheOther() throws Exception {
        String body = scopedBody("competing warehouse intake");
        var before = intakeCounts();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var requests = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return mvc.perform(post("/api/v1/cases").header("X-Mulino-Local-Role", "MANAGER")
                        .header("Idempotency-Key", "competing-intake-" + index)
                        .contentType("application/json").content(body)).andReturn().getResponse().getStatus();
            })).toList();
            start.countDown();
            assertThat(List.of(requests.get(0).get(20, TimeUnit.SECONDS),
                    requests.get(1).get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
            var after = intakeCounts();
            for (String table : List.of("cases", "work_items", "runs", "request_idempotency", "planning_cases")) {
                assertThat(after.get(table)).as(table).isEqualTo(before.get(table) + 1);
            }
            assertThat(after.get("case_participants")).isEqualTo(before.get("case_participants") + 2);
        } finally {
            removeScopedIntakes("competing warehouse intake", "competing-intake-%");
        }
    }

    private String scopedBody(String objective) {
        long warehouse = jdbc.sql("INSERT INTO warehouses(name,type,plant_id) VALUES ('Intake conflict test','AMBIENT','INTAKE-CONFLICT') RETURNING warehouse_id")
                .query(Long.class).single();
        jdbc.sql("INSERT INTO planning_policies(warehouse_id,history_start_date) VALUES (:id,CURRENT_DATE)")
                .param("id", warehouse).update();
        jdbc.sql("INSERT INTO products(name,sku,unit,expiry_days) VALUES ('Intake conflict test','INTAKE-CONFLICT','CASE',180)").update();
        return mapper.writeValueAsString(Map.of("objective", objective, "replenishment",
                Map.of("warehouseId", warehouse, "productSkus", List.of("INTAKE-CONFLICT"))));
    }

    private Map<String, Long> intakeCounts() {
        var counts = new java.util.HashMap<String, Long>();
        for (String table : List.of("cases", "case_participants", "work_items", "runs", "request_idempotency", "planning_cases")) {
            counts.put(table, jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single());
        }
        return counts;
    }

    private void removeScopedIntakes(String objective, String keyPattern) {
        for (String table : List.of("runs", "planning_cases", "case_participants", "work_items")) {
            jdbc.sql("DELETE FROM " + table + " WHERE case_id IN (SELECT case_id FROM cases WHERE objective=:objective)")
                    .param("objective", objective).update();
        }
        jdbc.sql("DELETE FROM cases WHERE objective=:objective").param("objective", objective).update();
        jdbc.sql("DELETE FROM request_idempotency WHERE scope LIKE 'case.create:%' AND request_key LIKE :pattern")
                .param("pattern", keyPattern).update();
        jdbc.sql("DELETE FROM planning_policies WHERE warehouse_id IN (SELECT warehouse_id FROM warehouses WHERE plant_id='INTAKE-CONFLICT')").update();
        jdbc.sql("DELETE FROM warehouses WHERE plant_id='INTAKE-CONFLICT'").update();
        jdbc.sql("DELETE FROM products WHERE sku='INTAKE-CONFLICT'").update();
        jdbc.sql("DELETE FROM users WHERE email='local-manager@mulino.local'").update();
    }

    private String create(String body,String key) throws Exception {
        return mvc.perform(post("/api/v1/cases").header("X-Mulino-Local-Role","MANAGER").header("Idempotency-Key",key).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    private long anonymous(String body) throws Exception {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        return intake.createCase(mapper.readValue(body,CreateCaseRequest.class),"ignored").caseId();
    }
}

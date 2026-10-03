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
    private String create(String body,String key) throws Exception {
        return mvc.perform(post("/api/v1/cases").header("X-Mulino-Local-Role","MANAGER").header("Idempotency-Key",key).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    private long anonymous(String body) throws Exception {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        return intake.createCase(mapper.readValue(body,CreateCaseRequest.class),"ignored").caseId();
    }
}

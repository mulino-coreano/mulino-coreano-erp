package com.mulinocoreano.backend.interfacepackage;

import com.mulinocoreano.backend.security.HumanActor;
import com.mulinocoreano.backend.security.WithTestActor;
import com.mulinocoreano.backend.idempotency.RequestIdempotency;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest @AutoConfigureMockMvc @Transactional
@WithTestActor(capabilities={"erp:read","work:write"})
class CaseLifecycleIntakeIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoSpyBean(name="planningClock") Clock planningClock;
    @MockitoSpyBean RequestIdempotency idempotency;

    @Test
    void intakeRecordsActualHumanAndQueuesExactlyOneInitialRun() throws Exception {
        long actor=((HumanActor)SecurityContextHolder.getContext().getAuthentication().getPrincipal()).userId();
        String ref=create("one",Map.of("objective","재보충 계획"));
        assertThat(jdbc.sql("SELECT opened_by_user_id FROM cases WHERE case_ref=:ref").param("ref",ref).query(Long.class).single()).isEqualTo(actor);
        assertThat(jdbc.sql("SELECT count(*) FROM runs r JOIN cases c USING(case_id) WHERE c.case_ref=:ref AND r.status='QUEUED'").param("ref",ref).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void sameRequestReplaysItsCaseAndInitialRunWhileConflictingInputIsRejected() throws Exception {
        String key=UUID.randomUUID().toString();
        String ref=create(key,Map.of("objective","동일 목표"));
        assertThat(create(key,Map.of("objective","동일 목표"))).isEqualTo(ref);
        assertThat(jdbc.sql("SELECT count(*) FROM runs r JOIN cases c USING(case_id) WHERE c.case_ref=:ref").param("ref",ref).query(Long.class).single()).isEqualTo(1);
        mvc.perform(post("/api/v1/cases").header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"objective\":\"다른 목표\"}")).andExpect(status().isConflict());
    }

    @Test
    void missingIdempotencyKeyDoesNotCreateACase() throws Exception {
        long before=countCases();
        mvc.perform(post("/api/v1/cases").contentType(MediaType.APPLICATION_JSON).content("{\"objective\":\"키 없음\"}"))
                .andExpect(status().isBadRequest());
        assertThat(countCases()).isEqualTo(before);
    }

    @Test
    void resolvesProductScopeAndReusesOnlyAnEquivalentActivePlanningCase() throws Exception {
        long warehouse=jdbc.sql("INSERT INTO warehouses(name,type) VALUES('Intake plant','AMBIENT') RETURNING warehouse_id").query(Long.class).single();
        jdbc.sql("INSERT INTO planning_policies(warehouse_id,history_start_date) VALUES(:id,CURRENT_DATE-56)").param("id",warehouse).update();
        String sku="INTAKE-"+UUID.randomUUID().toString().substring(0,8);
        long product=jdbc.sql("INSERT INTO products(name,sku,unit,expiry_days) VALUES('Intake product',:sku,'CASE',180) RETURNING product_id")
                .param("sku",sku).query(Long.class).single();
        var body=Map.of("objective","완제품 보충","replenishment",Map.of("warehouseId",warehouse,"productSkus",java.util.List.of(sku,sku)));
        String ref=create("first",body);
        mvc.perform(post("/api/v1/cases").header("Idempotency-Key","second").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.caseRef").value(ref)).andExpect(jsonPath("$.reused").value(true));
        assertThat(jdbc.sql("SELECT (metadata->'replenishment'->>'warehouseId')::bigint FROM cases WHERE case_ref=:ref").param("ref",ref).query(Long.class).single()).isEqualTo(warehouse);
        assertThat(jdbc.sql("SELECT (metadata->'replenishment'->'productIds'->>0)::bigint FROM cases WHERE case_ref=:ref").param("ref",ref).query(Long.class).single()).isEqualTo(product);
        assertThat(jdbc.sql("SELECT count(*) FROM planning_cases WHERE warehouse_id=:id").param("id",warehouse).query(Long.class).single()).isEqualTo(1);
        mvc.perform(post("/api/v1/cases").header("Idempotency-Key","third").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("objective","다른 목표","replenishment",body.get("replenishment")))))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidScopeRollsBackTheWholeIntake() throws Exception {
        long before=countCases();
        mvc.perform(post("/api/v1/cases").header("Idempotency-Key","invalid").contentType(MediaType.APPLICATION_JSON)
                .content("{\"objective\":\"보충\",\"replenishment\":{\"warehouseId\":999999999,\"productSkus\":[\"MISSING\"]}}"))
                .andExpect(status().isBadRequest());
        assertThat(countCases()).isEqualTo(before);
    }

    @Test
    void missingCaseIsAnExplicitNotFoundResponse() throws Exception {
        mvc.perform(get("/api/v1/cases/CASE-MISSING")).andExpect(status().isNotFound());
    }

    @Test
    void sameExplicitDeadlineIsReusableOnTheFollowingDay() throws Exception {
        long warehouse=jdbc.sql("INSERT INTO warehouses(name,type) VALUES('Deadline plant','AMBIENT') RETURNING warehouse_id").query(Long.class).single();
        jdbc.sql("INSERT INTO planning_policies(warehouse_id,history_start_date) VALUES(:id,CURRENT_DATE-56)").param("id",warehouse).update();
        String sku="DEADLINE-"+UUID.randomUUID().toString().substring(0,8);
        jdbc.sql("INSERT INTO products(name,sku,unit,expiry_days) VALUES('Deadline product',:sku,'CASE',180)").param("sku",sku).update();
        var now=planningClock.instant();
        var body=Map.of("objective","마감일까지 보충","replenishment",Map.of("warehouseId",warehouse,"productSkus",java.util.List.of(sku),
                "targetDate",LocalDate.now(planningClock).plusDays(10).toString()));
        String ref=create("today",body);
        doReturn(now.plus(1,ChronoUnit.DAYS)).when(planningClock).instant();
        assertThat(create("tomorrow",body)).isEqualTo(ref);
    }

    @Test
    void actorEligibilityIsLockedBeforePotentiallyBlockingRequestCoordination() throws Exception {
        var observed=new AtomicBoolean();
        doAnswer(invocation->{
            assertThat(jdbc.sql("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE pid=pg_backend_pid() AND relation='users'::regclass AND mode='RowShareLock')")
                    .query(Boolean.class).single()).isTrue();
            observed.set(true);
            return invocation.callRealMethod();
        }).when(idempotency).execute(startsWith("case.create:"),anyString(),any(),any());
        create("lock",Map.of("objective","권한 잠금"));
        assertThat(observed).isTrue();
    }

    private String create(String key,Object body) throws Exception {
        String response=mvc.perform(post("/api/v1/cases").header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).get("caseRef").asText();
    }
    private long countCases() {return jdbc.sql("SELECT count(*) FROM cases").query(Long.class).single();}
}

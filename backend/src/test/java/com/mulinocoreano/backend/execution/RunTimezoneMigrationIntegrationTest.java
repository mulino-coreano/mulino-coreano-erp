package com.mulinocoreano.backend.execution;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 목표 4: 업그레이드와 조회 시간대가 실행 시점·소요 시간을 바꾸지 않는다. */
@SpringBootTest(properties = {
        "spring.flyway.schemas=run_timezone_migration_it",
        "spring.flyway.clean-disabled=false",
        "spring.flyway.init-sqls=CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public",
        "spring.datasource.hikari.schema=run_timezone_migration_it"
})
class RunTimezoneMigrationIntegrationTest {
    @Autowired com.mulinocoreano.backend.interfacepackage.RunSchedulingRepository reads;
    @Autowired Flyway flyway;
    @Autowired DataSource dataSource;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager manager;

    @Test
    void v27PreservesKnownSeoulWallClockInstantsAndNullFinishAcrossSessionZones() {
        assertThat(flyway.getConfiguration().getSchemas()).containsExactly("run_timezone_migration_it");
        flyway.clean();
        Flyway.configure().dataSource(dataSource).schemas("run_timezone_migration_it")
                .defaultSchema("run_timezone_migration_it").target("26").load().migrate();
        jdbc.sql("INSERT INTO cases(case_ref,title,objective,intent_type) VALUES('CASE-TIME','Time preservation','Time preservation','ACT')").update();
        jdbc.sql("""
                INSERT INTO work_items(work_item_ref,case_id,title,assigned_agent_id)
                SELECT 'WI-TIME',case_id,'Time preservation',agent_id
                FROM cases CROSS JOIN agents WHERE case_ref='CASE-TIME' AND agent_key='ORCHESTRATOR'
                """).update();
        jdbc.sql("""
                INSERT INTO runs(run_ref,agent_id,case_id,work_item_id,runtime,status,started_at,finished_at,claimed_at)
                SELECT 'RUN-LEGACY-TIME',assigned_agent_id,case_id,work_item_id,'CODEX','COMPLETED',
                    TIMESTAMP '2026-10-03 09:00:00',TIMESTAMP '2026-10-03 09:00:00.4',TIMESTAMPTZ '2026-10-03 00:00:00Z'
                FROM work_items WHERE work_item_ref='WI-TIME'
                """).update();
        jdbc.sql("""
                INSERT INTO runs(run_ref,agent_id,case_id,work_item_id,runtime,status,started_at)
                SELECT 'RUN-UNFINISHED-TIME',assigned_agent_id,case_id,work_item_id,'CODEX','QUEUED',
                    TIMESTAMP '2026-10-03 09:00:00'
                FROM work_items WHERE work_item_ref='WI-TIME'
                """).update();
        flyway.migrate();
        for (String zone : List.of("UTC", "Asia/Seoul")) {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.sql("SET LOCAL TIME ZONE '" + zone + "'").update();
                var times = jdbc.sql("SELECT started_at,finished_at,extract(epoch FROM finished_at-claimed_at) FROM runs WHERE run_ref='RUN-LEGACY-TIME'")
                        .query((rs,row) -> new Times(rs.getObject(1,OffsetDateTime.class).toInstant(),
                                rs.getObject(2,OffsetDateTime.class).toInstant(),rs.getDouble(3))).single();
                assertThat(times.start()).isEqualTo(Instant.parse("2026-10-03T00:00:00Z"));
                long runId=jdbc.sql("SELECT run_id FROM runs WHERE run_ref='RUN-LEGACY-TIME'").query(Long.class).single();
                assertThat(reads.load(runId).startedAt()).isEqualTo(times.start());
                assertThat(times.finish()).isEqualTo(Instant.parse("2026-10-03T00:00:00.4Z"));
                assertThat(times.seconds()).isEqualTo(0.4);
                assertThat(jdbc.sql("SELECT finished_at IS NULL FROM runs WHERE run_ref='RUN-UNFINISHED-TIME'").query(Boolean.class).single()).isTrue();
            });
        }
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE table_schema='run_timezone_migration_it' AND table_name='runs' AND column_name IN ('started_at','finished_at','claimed_at','lease_expires_at') AND data_type='timestamp with time zone'").query(Long.class).single()).isEqualTo(4);
        assertThat(jdbc.sql("SELECT data_type FROM information_schema.columns WHERE table_schema='run_timezone_migration_it' AND table_name='work_items' AND column_name='resolved_at'").query(String.class).single()).isEqualTo("timestamp without time zone");
    }

    private record Times(Instant start, Instant finish, double seconds) {}
}

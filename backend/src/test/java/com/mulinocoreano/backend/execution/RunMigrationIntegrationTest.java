package com.mulinocoreano.backend.execution;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={"spring.flyway.schemas=run_migration_it","spring.flyway.clean-disabled=false","spring.datasource.hikari.schema=run_migration_it"})
class RunMigrationIntegrationTest {
    @Autowired Flyway latest;
    @Autowired DataSource dataSource;
    @Autowired JdbcClient jdbc;
    @Test void legacyRunningBecomesAbortedWithHistoryAndReadyWorkWithoutChangingSnapshot() {
        assertThat(latest.getConfiguration().getSchemas()).containsExactly("run_migration_it");
        latest.clean();
        Flyway.configure().dataSource(dataSource).schemas("run_migration_it").defaultSchema("run_migration_it").target("19").load().migrate();
        long cid=jdbc.sql("INSERT INTO cases(case_ref,title,objective,intent_type) VALUES('CASE-LEGACY','Legacy','Keep record','ACT') RETURNING case_id").query(Long.class).single();
        long wi=jdbc.sql("INSERT INTO work_items(work_item_ref,case_id,title,status,assigned_agent_id) SELECT 'WI-LEGACY',:c,'Legacy','IN_PROGRESS',agent_id FROM agents WHERE agent_key='ORCHESTRATOR' RETURNING work_item_id").param("c",cid).query(Long.class).single();
        jdbc.sql("INSERT INTO runs(run_ref,agent_id,case_id,work_item_id,runtime,status,context_snapshot) SELECT 'RUN-LEGACY',assigned_agent_id,case_id,work_item_id,'CODEX','RUNNING','{\"historicalFact\":\"untouched\"}'::jsonb FROM work_items WHERE work_item_id=:w").param("w",wi).update();
        latest.migrate();
        assertThat(jdbc.sql("SELECT status::text FROM runs WHERE run_ref='RUN-LEGACY'").query(String.class).single()).isEqualTo("ABORTED");
        assertThat(jdbc.sql("SELECT status::text FROM work_items WHERE work_item_ref='WI-LEGACY'").query(String.class).single()).isEqualTo("READY");
        assertThat(jdbc.sql("SELECT context_snapshot->>'historicalFact' FROM runs WHERE run_ref='RUN-LEGACY'").query(String.class).single()).isEqualTo("untouched");
        assertThat(jdbc.sql("SELECT count(*) FROM events WHERE event_type='LEGACY_RUN_ABORTED'").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT execution_context IS NULL AND claimed_at IS NULL FROM runs WHERE run_ref='RUN-LEGACY'").query(Boolean.class).single()).isTrue();
    }
}

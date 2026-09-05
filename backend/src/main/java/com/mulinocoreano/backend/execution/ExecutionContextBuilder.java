package com.mulinocoreano.backend.execution;

import com.mulinocoreano.backend.interfacepackage.ContextSnapshotService;
import com.mulinocoreano.backend.planning.PlanningSnapshotRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import com.mulinocoreano.backend.planning.CanonicalJson;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reconstructs current facts without replacing the original Run or plan audit snapshots. */
@Component
public class ExecutionContextBuilder {
    private final ContextSnapshotService contexts;
    private final PlanningSnapshotRepository snapshots;
    private final JdbcClient jdbc;
    private final CanonicalJson json;
    private final Clock clock;
    public ExecutionContextBuilder(ContextSnapshotService contexts,PlanningSnapshotRepository snapshots,JdbcClient jdbc,CanonicalJson json,@Qualifier("planningClock") Clock clock) {
        this.contexts=contexts;this.snapshots=snapshots;this.jdbc=jdbc;this.json=json;this.clock=clock;
    }
    public Map<String,Object> build(String caseRef,long caseId) {
        var context=new LinkedHashMap<String,Object>(contexts.build(caseRef));
        context.put("caseRef",caseRef);
        context.put("caseMetadata",json.readTree(jdbc.sql("SELECT COALESCE(metadata,'{}'::jsonb)::text FROM cases WHERE case_id=:id")
                .param("id",caseId).query(String.class).single()));
        jdbc.sql("""
                SELECT plan_ref,version,warehouse_id,horizon_days,source_snapshot::text,result::text FROM replenishment_plans
                WHERE case_id=:id ORDER BY version DESC LIMIT 1
                """).param("id",caseId).query((rs,n)->new PriorPlan(rs.getString(1),rs.getInt(2),rs.getLong(3),rs.getInt(4),rs.getString(5),rs.getString(6))).optional().ifPresent(plan->{
            var source=json.readTree(plan.source());
            var productIds=java.util.stream.StreamSupport.stream(source.path("products").spliterator(),false)
                    .map(product->product.path("item").path("id").asLong()).distinct().sorted().toList();
            context.put("latestPlan",Map.of("ref",plan.ref(),"version",plan.version(),"sourceSnapshot",source,"result",json.readTree(plan.result())));
            context.put("currentBusinessFacts",snapshots.load(plan.warehouse(),productIds,LocalDate.now(clock),plan.horizon()));
        });
        return context;
    }
    private record PriorPlan(String ref,int version,long warehouse,int horizon,String source,String result) {}
}

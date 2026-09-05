package com.mulinocoreano.backend.execution;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;

/** All execution writes acquire locks in Work Item -> Run -> Case -> Agent order. */
@Repository
public class RunLeaseRepository {
    private final JdbcClient jdbc;
    public RunLeaseRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

    public RunRow lockByRef(String ref) {
        requireTransaction();
        Long id=jdbc.sql("SELECT run_id FROM runs WHERE run_ref=:ref").param("ref",ref).query(Long.class).optional().orElseThrow(RunLeaseRepository::stale);
        return lock(id);
    }
    public RunRow lock(long id) {
        requireTransaction();
        Long wi=jdbc.sql("SELECT work_item_id FROM runs WHERE run_id=:id AND work_item_id IS NOT NULL").param("id",id).query(Long.class).optional().orElseThrow(RunLeaseRepository::stale);
        jdbc.sql("SELECT work_item_id FROM work_items WHERE work_item_id=:id FOR UPDATE").param("id",wi).query(Long.class).single();
        jdbc.sql("SELECT run_id FROM runs WHERE run_id=:id FOR UPDATE").param("id",id).query(Long.class).single();
        // Obtain current ownership only after locking its mutable sources.
        jdbc.sql("SELECT c.case_id FROM cases c JOIN runs r ON r.case_id=c.case_id WHERE r.run_id=:id FOR SHARE OF c").param("id",id).query(Long.class).single();
        jdbc.sql("SELECT a.agent_id FROM agents a JOIN runs r ON r.agent_id=a.agent_id WHERE r.run_id=:id FOR SHARE OF a").param("id",id).query(Long.class).single();
        return jdbc.sql("""
                SELECT r.run_id,r.run_ref,r.case_id,r.work_item_id,r.agent_id,r.runtime,r.status::text,
                  r.lease_owner,r.lease_token_hash,r.capability_token_hash,r.lease_expires_at,r.claimed_at,r.attempt,r.outcome,
                  c.case_ref,c.status::text AS case_status,w.work_item_ref,w.status::text AS wi_status,
                  a.agent_key,a.is_active, (w.case_id=r.case_id AND w.assigned_agent_id=r.agent_id AND w.assigned_user_id IS NULL) AS assignment_valid,
                  (r.lease_expires_at>clock_timestamp() AND r.claimed_at+interval '600 seconds'>clock_timestamp()) AS lease_valid
                FROM runs r JOIN work_items w ON w.work_item_id=r.work_item_id
                JOIN cases c ON c.case_id=r.case_id JOIN agents a ON a.agent_id=r.agent_id
                WHERE r.run_id=:id
                """).param("id",id).query((rs,n)->new RunRow(rs.getLong("run_id"),rs.getString("run_ref"),rs.getLong("case_id"),rs.getLong("work_item_id"),rs.getLong("agent_id"),
                rs.getString("runtime"),rs.getString("status"),rs.getString("lease_owner"),rs.getString("lease_token_hash"),rs.getString("capability_token_hash"),
                rs.getTimestamp("lease_expires_at")==null?null:rs.getTimestamp("lease_expires_at").toInstant(),rs.getTimestamp("claimed_at")==null?null:rs.getTimestamp("claimed_at").toInstant(),rs.getInt("attempt"),rs.getString("outcome"),
                rs.getString("case_ref"),rs.getString("case_status"),rs.getString("work_item_ref"),rs.getString("wi_status"),rs.getString("agent_key"),rs.getBoolean("is_active"),rs.getBoolean("assignment_valid"),rs.getBoolean("lease_valid"))).single();
    }
    public void requireLive(RunRow row) {
        if(!"RUNNING".equals(row.status()) || !row.leaseValid() || !row.currentAssignment() || !"IN_PROGRESS".equals(row.workStatus())) throw stale();
    }
    public static void requireTransaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Run authorization requires an existing transaction");
    }
    public static ResponseStatusException stale() { return new ResponseStatusException(HttpStatus.CONFLICT,"STALE_LEASE"); }

    public record RunRow(long id,String ref,long caseId,long workId,long agentId,String runtime,String status,String owner,String leaseHash,String capabilityHash,
                         Instant expiresAt,Instant claimedAt,int attempt,String outcome,String caseRef,String caseStatus,String workRef,String workStatus,String agentKey,boolean active,boolean assigned,boolean leaseValid) {
        public boolean currentAssignment() { return assigned && active && !java.util.Set.of("RESOLVED","CLOSED","CANCELLED").contains(caseStatus); }
        public boolean terminal() { return java.util.Set.of("COMPLETED","FAILED","ABORTED").contains(status); }
    }
}

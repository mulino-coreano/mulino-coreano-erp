package com.mulinocoreano.backend.interfacepackage;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.mulinocoreano.backend.planning.CanonicalJson;

@SpringBootTest
@Transactional
class CaseOverviewIntegrationTest {
    @Autowired CaseOverviewService service;
    @Autowired JdbcClient jdbc;
    @Autowired CanonicalJson json;

    @Test void humanAssignmentsAllWaitsAndRemainingResponsibilitySurviveRead() {
        var ref="CASE-"+shortId(); long id=businessCase(ref,"Purchasing completed; inbound remains",null);
        long user=jdbc.sql("INSERT INTO users(name,email,password,role) VALUES ('Human owner',:email,'DO-NOT-EXPOSE','MANAGER') RETURNING user_id")
            .param("email",shortId()+"@test.invalid").query(Long.class).single();
        jdbc.sql("INSERT INTO case_participants(case_id,actor_type,user_id,role) VALUES (:id,'USER',:user,'입고 담당')")
            .param("id",id).param("user",user).update();
        jdbc.sql("INSERT INTO work_items(work_item_ref,case_id,title,status,resolved_at) VALUES (:ref,:id,'Purchasing','DONE',CURRENT_TIMESTAMP)")
            .param("ref","WI-"+shortId()).param("id",id).update();
        long work=jdbc.sql("INSERT INTO work_items(work_item_ref,case_id,title,description,status,assigned_user_id,due_at) VALUES (:ref,:id,'Inbound','생산·입고 확인','WAITING',:user,CURRENT_TIMESTAMP+INTERVAL '1 day') RETURNING work_item_id")
            .param("ref","WI-"+shortId()).param("id",id).param("user",user).query(Long.class).single();
        for(String reason:List.of("Delivery confirmation","Production responsibility"))
            jdbc.sql("INSERT INTO waiting_conditions(waiting_ref,work_item_id,condition_type,reason) VALUES (:ref,:work,'EXTERNAL_DATA',:reason)")
                .param("ref","WAIT-"+shortId()).param("work",work).param("reason",reason).update();
        jdbc.sql("INSERT INTO attention_requests(case_id,work_item_id,reason_type,title,question,consequence,suggested_scope) VALUES (:id,:work,'MATERIAL_EXCEPTION','입고 확인','수령 일자를 확인해 주세요','생산 일정 지연','THIS_CASE')")
            .param("id",id).param("work",work).update();
        var before=counts(); var result=service.overview(ref); var encoded=json.write(result);
        var attention=(List<Map<String,Object>>)result.get("attention");
        assertThat(attention).hasSize(1);
        assertThat(attention.getFirst()).containsEntry("version",1).containsEntry("suggestedScope","THIS_CASE")
            .containsEntry("consequence","생산 일정 지연");
        assertThat(counts()).isEqualTo(before);
        assertThat(encoded).contains("Human owner","Delivery confirmation","Production responsibility","입고 담당")
            .doesNotContain("DO-NOT-EXPOSE","leaseToken","capability","contextSnapshot","sourceSnapshot");
        assertThat(((Map<?,?>)result.get("summary")).get("remainingWorkCount")).isEqualTo(1);
        var remaining=(List<Map<String,Object>>)result.get("remainingObligations");
        assertThat(remaining).hasSize(1); assertThat((List<?>)remaining.getFirst().get("activeWaits")).hasSize(2);
        assertThat(remaining.getFirst()).containsEntry("assignedUserId",user);
    }
    @Test void searchTreatsSqlAndWildcardsLiterallyAndCombinesSkuStatus() {
        String suffix=shortId(); String title="Literal %_ ' OR 1=1 -- "+suffix;
        String sku="SKU-"+suffix;
        businessCase("CASE-"+shortId(),title,json.write(Map.of("replenishment",Map.of("productSkus",List.of(sku)))));
        businessCase("CASE-"+shortId(),"Unrelated "+suffix,null);
        assertThat(service.search("%_ ' OR 1=1 --",sku,"OPEN")).hasSize(1);
        assertThat(service.search("%_ ' OR 1=1 --",sku+"-OTHER","OPEN")).isEmpty();
        assertThat(service.search("%_ ' OR 1=1 --",sku,"WAITING")).isEmpty();
        assertThat(service.search(title,null,null).getFirst().summary()).containsKey("nextActions");
        assertThatThrownBy(() -> service.search("x".repeat(201),null,null)).isInstanceOf(InvalidInterfaceRequestException.class);
    }
    @Test void missingCaseIs404AndDoesNotCreateCase() {
        var before=counts();
        assertThatThrownBy(() -> service.overview("CASE-missing")).isInstanceOfSatisfying(ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode().value()).isEqualTo(404));
        assertThat(counts()).isEqualTo(before);
    }
    private long businessCase(String ref,String title,String metadata) {
        return jdbc.sql("INSERT INTO cases(case_ref,title,objective,intent_type,metadata) VALUES (:ref,:title,:title,'ACT',CAST(:metadata AS jsonb)) RETURNING case_id")
            .param("ref",ref).param("title",title).param("metadata",metadata).query(Long.class).single();
    }
    private List<Long> counts() {
        return List.of(jdbc.sql("SELECT count(*) FROM cases").query(Long.class).single(),
            jdbc.sql("SELECT count(*) FROM work_items").query(Long.class).single(),
            jdbc.sql("SELECT count(*) FROM runs").query(Long.class).single(),
            jdbc.sql("SELECT count(*) FROM events").query(Long.class).single());
    }
    private String shortId() { return UUID.randomUUID().toString().substring(0,8); }
}

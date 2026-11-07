package com.mulinocoreano.backend.scenario;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.SimpleThreadScope;
import org.springframework.test.context.support.TestPropertySourceUtils;
import static org.assertj.core.api.Assertions.assertThat;

/** 목표 5: scoped 업무 상태와 실제 서버 port를 전달하여 컨테이너 CLI를 연결한다. */
class ScenarioWorldTest {
    @Test void scopedProxyRetainsTheCaseAndActualServerPort() {
        try (var context=new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerScope("cucumber-glue",new SimpleThreadScope());
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,"local.server.port=54321");
            context.registerBean(ScenarioWorld.class);
            context.refresh();
            var world=context.getBean(ScenarioWorld.class);
            assertThat(org.springframework.aop.support.AopUtils.isCglibProxy(world)).isTrue();
            world.caseRef("CASE-PROXY");world.approvalId(42L);
            world.pendingEvidence().add(java.util.Map.of("approvalStatus","PENDING","appliedPurchaseOrders",0));
            assertThat(world.port()).isEqualTo(54321);
            assertThat(java.net.URI.create(world.apiBase()).getPort()).isEqualTo(54321);
            assertThat(world.caseRef()).isEqualTo("CASE-PROXY");
            assertThat(world.approvalId()).isEqualTo(42L);
            assertThat(world.pendingEvidence()).containsExactly(java.util.Map.of("approvalStatus","PENDING","appliedPurchaseOrders",0));
        }
    }
}

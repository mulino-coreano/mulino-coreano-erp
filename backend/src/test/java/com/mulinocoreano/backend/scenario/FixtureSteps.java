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
        // production_lots에는 remaining_quantity가 없다(그 열은 raw_material_lots 전용). 완제품
        // LOT은 quantity 하나만 가지며, 활성·미만료 LOT의 quantity 합이 "가용 재고"다.
        BigDecimal usable = jdbc.sql("""
                SELECT coalesce(sum(pl.quantity),0) FROM production_lots pl JOIN products p USING(product_id)
                WHERE p.sku='DEMO-AMR' AND pl.status='ACTIVE' AND pl.expiry_date >= DATE '2026-09-05'
                """).query(BigDecimal.class).single();
        assertThat(usable).isEqualByComparingTo(BigDecimal.valueOf(available));
        assertThat(available).isLessThan(demand + safety);
    }
}

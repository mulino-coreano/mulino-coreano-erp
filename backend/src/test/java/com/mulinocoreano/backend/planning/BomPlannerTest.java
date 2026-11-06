package com.mulinocoreano.backend.planning;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.mulinocoreano.backend.planning.BomPlanner.*;
import static org.assertj.core.api.Assertions.*;

class BomPlannerTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 5);
    private static final Item AMR = new Item(Kind.PRODUCT, 1, "CASE");
    private static final Item BSC = new Item(Kind.PRODUCT, 2, "CASE");
    private static final Item DOUGH = new Item(Kind.PRODUCT, 3, "KG");
    private static final Item FLOUR = new Item(Kind.MATERIAL, 10, "KG");
    private static final Item SUGAR = new Item(Kind.MATERIAL, 11, "KG");
    private final BomPlanner planner = new BomPlanner();

    @Test
    void aggregatesSharedSubassembliesBeforeNettingAndBatchRounding() {
        var result = planner.plan(TODAY, List.of(demand(AMR, 10, "10"), demand(BSC, 10, "10")),
                List.of(bom("AMR-1", AMR, "10", 2, component(DOUGH, "2")),
                        bom("BSC-1", BSC, "10", 2, component(DOUGH, "3")),
                        bom("DOUGH-1", DOUGH, "5", 1, component(FLOUR, "3"), component(SUGAR, "2"))),
                List.of(lot("dough-stock", DOUGH, "1", 0, 30, null, false)));
        var dough = result.production().stream().filter(p -> p.product().equals(DOUGH)).toList();
        assertThat(dough).hasSize(1);
        assertThat(dough.getFirst().netQuantity()).isEqualByComparingTo("4");
        assertThat(dough.getFirst().productionQuantity()).isEqualByComparingTo("5");
        assertThat(dough.getFirst().startDate()).isEqualTo(TODAY.plusDays(7));
        assertThat(result.materials()).anySatisfy(m -> {
            assertThat(m.material()).isEqualTo(FLOUR);
            assertThat(m.needDate()).isEqualTo(TODAY.plusDays(7));
            assertThat(m.netQuantity()).isEqualByComparingTo("3");
        });
        assertThat(result.materials()).anySatisfy(m -> {
            assertThat(m.material()).isEqualTo(SUGAR);
            assertThat(m.netQuantity()).isEqualByComparingTo("2");
        });
    }

    @Test
    void consumesFefoOnceAndCountsProjectedSupplyOnlyAfterArrival() {
        var lots = List.of(lot("late-po", FLOUR, "20", 9, 30, null, true),
                lot("later-expiry", FLOUR, "1", 0, 30, null, false),
                lot("first-expiry", FLOUR, "1", 0, 8, null, false),
                lot("hold", FLOUR, "30", 0, 30, "INBOUND_HOLD", false),
                lot("expired", FLOUR, "40", 0, 6, null, false),
                lot("on-time-po", FLOUR, "0.5", 7, 30, null, true));
        var result = planner.plan(TODAY, List.of(demand(AMR, 9, "10")),
                List.of(bom("AMR-1", AMR, "10", 2, component(FLOUR, "3"))), lots);
        assertThat(result.materials()).singleElement().satisfies(m -> {
            assertThat(m.grossQuantity()).isEqualByComparingTo("3");
            assertThat(m.suppliedQuantity()).isEqualByComparingTo("2.5");
            assertThat(m.netQuantity()).isEqualByComparingTo("0.5");
        });
        assertThat(result.allocations()).extracting(Allocation::sourceRef)
                .containsExactly("first-expiry", "later-expiry", "on-time-po");
        assertThat(result.allocations().getLast().projected()).isTrue();
        assertThat(result.exclusions()).extracting(Exclusion::reason)
                .contains("INBOUND_HOLD", "EXPIRED_BEFORE_USE", "NOT_YET_AVAILABLE");
    }

    @Test
    void carriesBatchSurplusToLaterDemandAndNeverReusesConsumedStock() {
        var result = planner.plan(TODAY, List.of(demand(AMR, 5, "3"), demand(AMR, 6, "4"), demand(AMR, 7, "4")),
                List.of(bom("AMR-1", AMR, "10", 1, component(FLOUR, "2"))), List.of());
        assertThat(result.production()).hasSize(2);
        assertThat(result.production().get(0).productionQuantity()).isEqualByComparingTo("10");
        assertThat(result.production().get(1).netQuantity()).isEqualByComparingTo("1");
        assertThat(result.materials().stream().map(MaterialRequirement::netQuantity).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("4");
    }

    @Test
    void expiresPlannedSurplusInsteadOfTreatingItAsPermanentStock() {
        var shortLife = new Bom("AMR-1", AMR, d("10"), 0, 1, TODAY.minusDays(30), TODAY.plusDays(90), List.of(component(FLOUR, "2")));
        var result = planner.plan(TODAY, List.of(demand(AMR, 1, "3"), demand(AMR, 4, "3")), List.of(shortLife), List.of());
        assertThat(result.production()).hasSize(2);
    }

    @Test
    void rejectsCyclesAmbiguousVersionsMissingBomAndImpossibleProductionDates() {
        assertThatThrownBy(() -> planner.plan(TODAY, List.of(demand(AMR, 10, "1")),
                List.of(bom("A", AMR, "1", 0, component(DOUGH, "1")), bom("D", DOUGH, "1", 0, component(AMR, "1"))), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("BOM_CYCLE");
        assertThatThrownBy(() -> planner.plan(TODAY, List.of(demand(AMR, 10, "1")),
                List.of(bom("A", AMR, "1", 0, component(FLOUR, "1")), bom("B", AMR, "1", 0, component(FLOUR, "1"))), List.of()))
                .hasMessageContaining("AMBIGUOUS_BOM");
        assertThatThrownBy(() -> planner.plan(TODAY, List.of(demand(AMR, 10, "1")), List.of(), List.of()))
                .hasMessageContaining("MISSING_BOM");
        assertThatThrownBy(() -> planner.plan(TODAY, List.of(demand(AMR, 0, "1")), List.of(bom("A", AMR, "1", 1, component(FLOUR, "1"))), List.of()))
                .hasMessageContaining("PRODUCTION_LEAD_TIME");
    }

    @Test
    void rejectsDuplicateSourcesUnitMismatchAndInvalidQuantities() {
        var stock = lot("same", AMR, "1", 0, 30, null, false);
        assertThatThrownBy(() -> planner.plan(TODAY, List.of(demand(AMR, 1, "1")), List.of(), List.of(stock, stock)))
                .hasMessageContaining("DUPLICATE_SUPPLY");
        var wrongUnit = new Item(Kind.PRODUCT, AMR.id(), "KG");
        assertThatThrownBy(() -> planner.plan(TODAY, List.of(demand(AMR, 1, "1")), List.of(), List.of(lot("wrong", wrongUnit, "1", 0, 30, null, false))))
                .hasMessageContaining("ITEM_UNIT_MISMATCH");
        assertThatThrownBy(() -> planner.plan(TODAY, List.of(demand(AMR, 1, "-1")), List.of(), List.of()))
                .hasMessageContaining("NEGATIVE_QUANTITY");
    }

    @Test
    void stockSatisfiedDemandDoesNotRequireManufacturingOrInventMaterialNeeds() {
        var result = planner.plan(TODAY, List.of(demand(AMR, 5, "2")), List.of(), List.of(lot("existing", AMR, "2", 0, 30, null, false)));
        assertThat(result.production()).isEmpty();
        assertThat(result.materials()).isEmpty();
        assertThat(result.allocations()).singleElement().satisfies(a -> assertThat(a.quantity()).isEqualByComparingTo("2"));
    }

    @Test
    void refusesVersionThatExpiresBeforeUseOrStartsAfterProductionStart() {
        var endsSoon = new Bom("A", AMR, d("1"), 1, 180, TODAY.minusDays(1), TODAY.plusDays(3), List.of(component(FLOUR, "1")));
        assertThatThrownBy(() -> planner.plan(TODAY, List.of(demand(AMR, 5, "1")), List.of(endsSoon), List.of()))
                .hasMessageContaining("MISSING_BOM");
        var startsLate = new Bom("A", AMR, d("1"), 3, 180, TODAY.plusDays(4), TODAY.plusDays(30), List.of(component(FLOUR, "1")));
        assertThatThrownBy(() -> planner.plan(TODAY, List.of(demand(AMR, 5, "1")), List.of(startsLate), List.of()))
                .hasMessageContaining("BOM_NOT_VALID_FOR_PRODUCTION");
    }

    @Test
    void timeDisjointBomDirectionsDoNotBecomeAnArtificialHorizonWideCycle() {
        var result = planner.plan(TODAY, List.of(demand(AMR, 5, "1"), demand(AMR, 15, "1")),
                timeDisjointBoms(), List.of());
        assertThat(result.production()).extracting(ProductionRequirement::bomRef)
                .containsExactlyInAnyOrder("A-old", "B-old", "A-new");
        assertThat(result.materials().stream().map(MaterialRequirement::netQuantity).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("2");
    }

    @Test
    void bothTimeDisjointDirectionsCanSupplyDifferentRootDemands() {
        var result = planner.plan(TODAY, List.of(demand(AMR, 5, "1"), demand(BSC, 15, "1")),
                timeDisjointBoms(), List.of());
        assertThat(result.production()).extracting(ProductionRequirement::bomRef)
                .containsExactlyInAnyOrder("A-old", "B-old", "A-new", "B-new");
        assertThat(result.materials().stream().map(MaterialRequirement::netQuantity).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("2");
    }

    @Test
    void netsEarlierComponentNeedFirstEvenWhenItsParentIsDueLater() {
        var result = planner.plan(TODAY, List.of(demand(AMR, 5, "1"), demand(BSC, 10, "1")),
                List.of(bom("A", AMR, "1", 0, component(DOUGH, "1")),
                        bom("B", BSC, "1", 8, component(DOUGH, "1")),
                        bom("D", DOUGH, "10", 0, component(FLOUR, "10"))), List.of());
        assertThat(result.production().stream().filter(p -> p.product().equals(DOUGH)).toList())
                .singleElement().satisfies(p -> {
                    assertThat(p.productionQuantity()).isEqualByComparingTo("10");
                    assertThat(p.needDate()).isEqualTo(TODAY.plusDays(2));
                });
        assertThat(result.materials().stream().map(MaterialRequirement::netQuantity).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("10");
    }

    private static List<Bom> timeDisjointBoms() {
        return List.of(new Bom("A-old", AMR, d("1"), 0, 180, TODAY, TODAY.plusDays(9), List.of(component(BSC, "1"))),
                new Bom("B-old", BSC, d("1"), 0, 180, TODAY, TODAY.plusDays(9), List.of(component(FLOUR, "1"))),
                new Bom("A-new", AMR, d("1"), 0, 180, TODAY.plusDays(10), TODAY.plusDays(30), List.of(component(FLOUR, "1"))),
                new Bom("B-new", BSC, d("1"), 0, 180, TODAY.plusDays(10), TODAY.plusDays(30), List.of(component(AMR, "1"))));
    }

    private static BigDecimal d(String n) { return new BigDecimal(n); }
    private static Demand demand(Item item, int day, String n) { return new Demand(item, TODAY.plusDays(day), d(n)); }
    private static Component component(Item item, String n) { return new Component(item, d(n)); }
    private static Bom bom(String ref, Item product, String output, int lead, Component... components) {
        return new Bom(ref, product, d(output), lead, 180, TODAY.minusDays(30), TODAY.plusDays(90), List.of(components));
    }
    private static StockLot lot(String ref, Item item, String n, int availableDay, int expiryDay, String exclusion, boolean projected) {
        return new StockLot(ref, item, d(n), TODAY.plusDays(availableDay), TODAY.plusDays(expiryDay), exclusion, projected);
    }
}

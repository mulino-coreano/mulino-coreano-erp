package com.mulinocoreano.backend.procurement;

import static org.assertj.core.api.Assertions.*;

import com.mulinocoreano.backend.planning.BomPlanner;
import com.mulinocoreano.backend.planning.PlanningSnapshotRepository;
import com.mulinocoreano.backend.planning.ReplenishmentCalculator;
import com.mulinocoreano.backend.planning.SupplierSelectionService;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

class PurchaseBundleAssemblerTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 5);
    private final PurchaseBundleAssembler assembler = new PurchaseBundleAssembler();

    @Test
    void groupsBySupplierAndOrdersLinesWithoutLosingIndividualDeliveryDates() {
        var input =
                input(
                        List.of(
                                spec(3, 9, 30, "KG", "KG", "1", "2", "20", 3),
                                spec(2, 7, 20, "KG", "KG", "1", "4", "10", 5),
                                spec(1, 7, 10, "KG", "KG", "1", "3", "1", 1)));
        var bundle = assemble(input);
        assertThat(bundle).isNotNull();
        assertThat(bundle.status()).isEqualTo("READY");
        assertThat(bundle.orders())
                .extracting(PurchaseBundle.Order::supplierId)
                .containsExactly(7L, 9L);
        var first = bundle.orders().getFirst();
        assertThat(first.lines())
                .extracting(PurchaseBundle.Line::materialId)
                .containsExactly(1L, 2L);
        assertThat(first.expectedDeliveryDate()).isEqualTo(TODAY.plusDays(5));
        assertThat(first.lines())
                .extracting(PurchaseBundle.Line::expectedDeliveryDate)
                .containsExactly(TODAY.plusDays(1), TODAY.plusDays(5));
        assertThat(first.totalKrw()).isEqualByComparingTo("43");
        assertThat(bundle.totalKrw()).isEqualByComparingTo("83");
        assertThat(bundle.targetDate()).isEqualTo(TODAY.plusDays(29));
        assertThatThrownBy(() -> bundle.orders().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.lines().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        var reversed = new ArrayList<>(input.calculation().purchases());
        Collections.reverse(reversed);
        assertThat(
                        assembler.assemble(
                                "PLAN-TEST",
                                1,
                                "1".repeat(64),
                                "2".repeat(64),
                                input.snapshot(),
                                calculation(reversed)))
                .isEqualTo(bundle);
    }

    @Test
    void convertsKilogramPurchasePriceToExactGramBasePriceAtNineDecimals() {
        var bundle =
                assemble(
                        input(
                                List.of(
                                        spec(
                                                1,
                                                7,
                                                10,
                                                "KG",
                                                "G",
                                                "1000",
                                                "2",
                                                "123456789012.123456",
                                                2))));
        assertThat(bundle).isNotNull();
        var line = bundle.orders().getFirst().lines().getFirst();
        assertThat(line.buyQuantity()).isEqualByComparingTo("2");
        assertThat(line.baseQuantity()).isEqualByComparingTo("2000");
        assertThat(line.buyUnitPrice()).isEqualByComparingTo("123456789012.123456");
        assertThat(line.baseUnitPrice()).isEqualByComparingTo("123456789.012123456");
        assertThat(line.lineAmountKrw()).isEqualByComparingTo("246913578024");
    }

    @Test
    void convertsGramPurchasesToKilogramBasePriceAndRoundsOnlyContractualKrw() {
        var bundle =
                assemble(input(List.of(spec(1, 7, 10, "G", "KG", "0.001", "1500", "1.234567", 2))));
        assertThat(bundle).isNotNull();
        var line = bundle.orders().getFirst().lines().getFirst();
        assertThat(line.baseQuantity()).isEqualByComparingTo("1.5");
        assertThat(line.baseUnitPrice()).isEqualByComparingTo("1234.567000000");
        assertThat(line.lineAmountKrw()).isEqualByComparingTo("1852");
        var half = assemble(input(List.of(spec(1, 7, 10, "KG", "KG", "1", "1", "0.5", 0))));
        assertThat(half.totalKrw()).isEqualByComparingTo("1");
    }

    @Test
    void emptyReadyCalculationReturnsNoPurchaseRequiredWithoutDummyOrders() {
        var bundle = assemble(input(List.of()));
        assertThat(bundle).isNotNull();
        assertThat(bundle.status()).isEqualTo("NO_PURCHASE_REQUIRED");
        assertThat(bundle.orders()).isEmpty();
        assertThat(bundle.totalKrw()).isEqualByComparingTo("0");
    }

    @Test
    void rejectsUnrepresentableOrOutOfRangeBasePricesInsteadOfRoundingThem() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                assemble(
                                        input(
                                                List.of(
                                                        spec(
                                                                1,
                                                                7,
                                                                10,
                                                                "KG",
                                                                "G",
                                                                "1000",
                                                                "1",
                                                                "0.0000001",
                                                                0)))))
                .withMessageContaining("BASE_UNIT_PRICE");
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                assemble(
                                        input(
                                                List.of(
                                                        spec(
                                                                1,
                                                                7,
                                                                10,
                                                                "KG",
                                                                "KG",
                                                                "1",
                                                                "1",
                                                                "1000000000000000",
                                                                0)))))
                .withMessageContaining("BASE_UNIT_PRICE");
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                assemble(
                                        input(
                                                List.of(
                                                        spec(
                                                                1, 7, 10, "KG", "KG", "3", "1", "1",
                                                                0)))))
                .withMessageContaining("CONVERSION");
    }

    @Test
    void rejectsUnknownSelectedTermAndChangedSelectedQuantity() {
        var original = input(List.of(spec(1, 7, 10, "KG", "KG", "1", "2", "10", 0)));
        var purchase = original.calculation().purchases().getFirst();
        var chosen = purchase.selection().chosen();
        var unknown =
                new SupplierSelectionService.Candidate(
                        chosen.supplierId(),
                        999,
                        chosen.buyUnit(),
                        chosen.purchaseQuantity(),
                        chosen.baseUnit(),
                        chosen.suppliedBaseQuantity(),
                        chosen.unitPrice(),
                        chosen.currency(),
                        chosen.totalAmount(),
                        chosen.expectedArrival(),
                        List.of());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> assemble(withCandidate(original, unknown)))
                .withMessageContaining("TERM");
        var changed =
                new SupplierSelectionService.Candidate(
                        chosen.supplierId(),
                        chosen.termId(),
                        chosen.buyUnit(),
                        d("3"),
                        chosen.baseUnit(),
                        d("3"),
                        chosen.unitPrice(),
                        chosen.currency(),
                        d("30"),
                        chosen.expectedArrival(),
                        List.of());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> assemble(withCandidate(original, changed)))
                .withMessageContaining("CANDIDATE");
    }

    @Test
    void rejectsMissingSourceEvidenceAndNonReadyOrInconsistentTotals() {
        var original = input(List.of(spec(1, 7, 10, "KG", "KG", "1", "2", "10", 0)));
        var s = original.snapshot();
        var incomplete =
                new PlanningSnapshotRepository.Snapshot(
                        s.warehouseId(),
                        s.asOf(),
                        s.horizonDays(),
                        s.safetyDays(),
                        s.historyStartDate(),
                        s.products(),
                        s.boms(),
                        s.supply(),
                        s.supplierTerms(),
                        s.certificates(),
                        List.of());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> assemble(new Input(incomplete, original.calculation())))
                .withMessageContaining("SOURCE");
        var c = original.calculation();
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                assemble(
                                        new Input(
                                                s,
                                                new ReplenishmentCalculator.Calculation(
                                                        "NEEDS_ATTENTION",
                                                        c.forecasts(),
                                                        c.requirements(),
                                                        c.purchases(),
                                                        null,
                                                        List.of(
                                                                new ReplenishmentCalculator.Issue(
                                                                        "NO_ELIGIBLE_SUPPLIER",
                                                                        "raw_materials:1"))))));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                assemble(
                                        new Input(
                                                s,
                                                new ReplenishmentCalculator.Calculation(
                                                        "READY",
                                                        c.forecasts(),
                                                        c.requirements(),
                                                        c.purchases(),
                                                        d("21"),
                                                        List.of()))))
                .withMessageContaining("TOTAL");
    }

    private PurchaseBundle assemble(Input input) {
        return assembler.assemble(
                "PLAN-TEST",
                1,
                "1".repeat(64),
                "2".repeat(64),
                input.snapshot(),
                input.calculation());
    }

    private static Input withCandidate(Input input, SupplierSelectionService.Candidate candidate) {
        var p = input.calculation().purchases().getFirst();
        var changed =
                new ReplenishmentCalculator.PurchaseRequirement(
                        p.material(),
                        p.firstNeedDate(),
                        p.netBaseQuantity(),
                        new SupplierSelectionService.SelectionResult(
                                candidate, List.of(candidate), List.of(), List.of(), "SELECTED"));
        return new Input(input.snapshot(), calculation(List.of(changed)));
    }

    private static ReplenishmentCalculator.Calculation calculation(
            List<ReplenishmentCalculator.PurchaseRequirement> purchases) {
        var materials =
                purchases.stream()
                        .map(
                                p ->
                                        new BomPlanner.MaterialRequirement(
                                                p.material(),
                                                p.firstNeedDate(),
                                                p.netBaseQuantity(),
                                                BigDecimal.ZERO,
                                                p.netBaseQuantity()))
                        .toList();
        return new ReplenishmentCalculator.Calculation(
                "READY",
                Map.of(),
                new BomPlanner.Result(List.of(), materials, List.of(), List.of()),
                purchases,
                purchases.stream()
                        .map(p -> p.selection().chosen().totalAmount())
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                List.of());
    }

    private static Input input(List<Spec> specs) {
        var facts = new ArrayList<PlanningSnapshotRepository.SourceFact>();
        facts.add(
                new PlanningSnapshotRepository.SourceFact(
                        "measurement_units:KG",
                        Map.of("code", "KG", "dimension", "MASS", "to_canonical_factor", d("1"))));
        facts.add(
                new PlanningSnapshotRepository.SourceFact(
                        "measurement_units:G",
                        Map.of(
                                "code",
                                "G",
                                "dimension",
                                "MASS",
                                "to_canonical_factor",
                                d("0.001"))));
        var terms = new TreeMap<Long, List<SupplierSelectionService.SupplierTerm>>();
        var purchases = new ArrayList<ReplenishmentCalculator.PurchaseRequirement>();
        for (Spec spec : specs) {
            var term = spec.term();
            terms.computeIfAbsent(spec.material(), ignored -> new ArrayList<>()).add(term);
            facts.add(
                    new PlanningSnapshotRepository.SourceFact(
                            "raw_materials:" + spec.material(),
                            Map.of(
                                    "raw_material_id",
                                    spec.material(),
                                    "name",
                                    "Material " + spec.material(),
                                    "unit",
                                    term.baseUnit())));
            var source = new LinkedHashMap<String, Object>();
            source.put("supplier_material_term_id", term.termId());
            source.put("raw_material_id", spec.material());
            source.put("supplier_id", term.supplierId());
            source.put("purchase_unit", term.buyUnit());
            source.put("base_quantity_per_purchase_unit", term.baseUnitsPerBuyUnit());
            source.put("unit_price", term.unitPrice());
            source.put("currency", "KRW");
            source.put("minimum_order_quantity", term.minimumOrderQuantity());
            source.put("order_multiple", term.orderMultiple());
            source.put("lead_time_days", term.leadTimeDays());
            source.put("valid_from", term.validFrom().toString());
            source.put("valid_to", null);
            source.put("required_cert_types", List.of());
            source.put("is_active", true);
            source.put("supplier_active", true);
            source.put("supplier_name", "Supplier " + term.supplierId());
            facts.add(
                    new PlanningSnapshotRepository.SourceFact(
                            "supplier_material_terms:" + term.termId(), source));
            BigDecimal base = spec.quantity().multiply(term.baseUnitsPerBuyUnit());
            var chosen =
                    new SupplierSelectionService.Candidate(
                            term.supplierId(),
                            term.termId(),
                            term.buyUnit(),
                            spec.quantity(),
                            term.baseUnit(),
                            base,
                            term.unitPrice(),
                            "KRW",
                            spec.quantity()
                                    .multiply(term.unitPrice())
                                    .setScale(0, RoundingMode.HALF_UP),
                            TODAY.plusDays(term.leadTimeDays()),
                            List.of());
            purchases.add(
                    new ReplenishmentCalculator.PurchaseRequirement(
                            new BomPlanner.Item(
                                    BomPlanner.Kind.MATERIAL, spec.material(), term.baseUnit()),
                            TODAY.plusDays(10),
                            base,
                            new SupplierSelectionService.SelectionResult(
                                    chosen, List.of(chosen), List.of(), List.of(), "SELECTED")));
        }
        var snapshot =
                new PlanningSnapshotRepository.Snapshot(
                        1,
                        TODAY,
                        30,
                        7,
                        TODAY.minusDays(56),
                        List.of(),
                        List.of(),
                        List.of(),
                        terms,
                        List.of(),
                        facts);
        return new Input(snapshot, calculation(purchases));
    }

    private static Spec spec(
            long material,
            long supplier,
            long term,
            String buy,
            String base,
            String factor,
            String quantity,
            String price,
            int lead) {
        return new Spec(
                material,
                new SupplierSelectionService.SupplierTerm(
                        supplier,
                        term,
                        true,
                        buy,
                        d(factor),
                        base,
                        d(price),
                        "KRW",
                        BigDecimal.ZERO,
                        BigDecimal.ONE,
                        lead,
                        TODAY,
                        null,
                        Set.of()),
                d(quantity));
    }

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private record Input(
            PlanningSnapshotRepository.Snapshot snapshot,
            ReplenishmentCalculator.Calculation calculation) {}

    private record Spec(
            long material, SupplierSelectionService.SupplierTerm term, BigDecimal quantity) {}
}

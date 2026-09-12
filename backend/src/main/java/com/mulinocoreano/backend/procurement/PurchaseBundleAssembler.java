package com.mulinocoreano.backend.procurement;

import com.mulinocoreano.backend.planning.BomPlanner;
import com.mulinocoreano.backend.planning.PlanningSnapshotRepository;
import com.mulinocoreano.backend.planning.ReplenishmentCalculator;
import com.mulinocoreano.backend.planning.SupplierSelectionService;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Pure assembly from server calculation evidence. No database or ERP write capability. */
@Service
public class PurchaseBundleAssembler {
    private static final BigDecimal MAX_BASE_PRICE = new BigDecimal("999999999999999.999999999");
    private static final BigDecimal MAX_KRW = new BigDecimal("999999999999999999999999999999");
    private static final List<BigDecimal> CONVERSIONS =
            List.of(new BigDecimal("0.001"), BigDecimal.ONE, new BigDecimal("1000"));
    private static final Comparator<PurchaseBundle.Line> LINE_ORDER =
            Comparator.comparingLong(PurchaseBundle.Line::materialId)
                    .thenComparingLong(PurchaseBundle.Line::sourceTermId)
                    .thenComparing(PurchaseBundle.Line::expectedDeliveryDate)
                    .thenComparing(PurchaseBundle.Line::needDate);
    private final SupplierSelectionService selections = new SupplierSelectionService();

    public PurchaseBundleAssembler() {}

    public PurchaseBundle assemble(
            String planRef,
            int version,
            String hash,
            String sourceHash,
            PlanningSnapshotRepository.Snapshot snapshot,
            ReplenishmentCalculator.Calculation calculation) {
        require(
                planRef != null
                        && !planRef.isBlank()
                        && planRef.length() <= 50
                        && version > 0
                        && digest(hash)
                        && digest(sourceHash),
                "INVALID_PLAN_IDENTITY");
        require(
                snapshot != null
                        && snapshot.warehouseId() > 0
                        && snapshot.asOf() != null
                        && snapshot.horizonDays() >= 1
                        && snapshot.horizonDays() <= 90,
                "INVALID_PLAN_SCOPE");
        require(
                calculation != null
                        && "READY".equals(calculation.status())
                        && calculation.purchases() != null
                        && calculation.issues() != null
                        && calculation.issues().isEmpty()
                        && calculation.totalAmount() != null,
                "READY_CALCULATION_REQUIRED");
        require(calculation.totalAmount().signum() >= 0, "INVALID_CALCULATION_TOTAL");
        var facts = new HashMap<String, Map<String, Object>>();
        for (var fact : snapshot.sourceFacts()) {
            require(
                    fact != null && facts.putIfAbsent(fact.sourceRef(), fact.values()) == null,
                    "DUPLICATE_SOURCE_FACT");
        }
        var groups = new TreeMap<Long, List<PurchaseBundle.Line>>();
        var names = new HashMap<Long, String>();
        var identities = new HashSet<LineIdentity>();
        LocalDate target = snapshot.asOf().plusDays(snapshot.horizonDays() - 1L);
        for (var purchase : calculation.purchases()) {
            require(
                    purchase != null
                            && purchase.material() != null
                            && purchase.material().kind() == BomPlanner.Kind.MATERIAL
                            && purchase.material().id() > 0
                            && purchase.firstNeedDate() != null
                            && !purchase.firstNeedDate().isBefore(snapshot.asOf())
                            && !purchase.firstNeedDate().isAfter(target)
                            && purchase.netBaseQuantity() != null
                            && purchase.netBaseQuantity().signum() > 0
                            && purchase.selection() != null
                            && "SELECTED".equals(purchase.selection().reason())
                            && purchase.selection().chosen() != null,
                    "INVALID_PURCHASE_REQUIREMENT");
            var chosen = purchase.selection().chosen();
            var terms = snapshot.supplierTerms().getOrDefault(purchase.material().id(), List.of());
            var matching = terms.stream().filter(term -> term.termId() == chosen.termId()).toList();
            require(
                    matching.size() == 1 && matching.getFirst().supplierId() == chosen.supplierId(),
                    "SELECTED_TERM_MISMATCH");
            var term = matching.getFirst();
            validateSource(facts, purchase.material(), term);
            BigDecimal basePrice = basePrice(term.unitPrice(), term.baseUnitsPerBuyUnit());
            SupplierSelectionService.Candidate expected;
            try {
                expected =
                        selections
                                .select(
                                        snapshot.asOf(),
                                        purchase.firstNeedDate(),
                                        purchase.netBaseQuantity(),
                                        purchase.material().unit(),
                                        terms,
                                        snapshot.certificates())
                                .chosen();
            } catch (IllegalArgumentException invalid) {
                throw invalid("INVALID_SELECTED_CANDIDATE");
            }
            require(sameCandidate(expected, chosen), "SELECTED_CANDIDATE_MISMATCH");
            BigDecimal amount =
                    krw(
                            chosen.purchaseQuantity()
                                    .multiply(chosen.unitPrice())
                                    .setScale(0, RoundingMode.HALF_UP));
            require(same(amount, chosen.totalAmount()), "SELECTED_CANDIDATE_AMOUNT_MISMATCH");
            var material = fact(facts, "raw_materials:" + purchase.material().id());
            var supplier = fact(facts, "supplier_material_terms:" + term.termId());
            String supplierName = text(supplier, "supplier_name");
            String priorName = names.putIfAbsent(chosen.supplierId(), supplierName);
            require(
                    priorName == null || priorName.equals(supplierName),
                    "INCONSISTENT_SUPPLIER_SOURCE");
            require(
                    identities.add(
                            new LineIdentity(
                                    purchase.material().id(),
                                    term.termId(),
                                    purchase.firstNeedDate())),
                    "DUPLICATE_PURCHASE_REQUIREMENT");
            var line =
                    new PurchaseBundle.Line(
                            purchase.material().id(),
                            text(material, "name"),
                            term.termId(),
                            chosen.buyUnit(),
                            chosen.purchaseQuantity(),
                            chosen.unitPrice(),
                            chosen.baseUnit(),
                            chosen.suppliedBaseQuantity(),
                            term.baseUnitsPerBuyUnit(),
                            basePrice,
                            purchase.firstNeedDate(),
                            chosen.expectedArrival(),
                            amount);
            groups.computeIfAbsent(chosen.supplierId(), ignored -> new ArrayList<>()).add(line);
        }
        var orders = new ArrayList<PurchaseBundle.Order>();
        BigDecimal total = BigDecimal.ZERO;
        for (var entry : groups.entrySet()) {
            var lines = entry.getValue().stream().sorted(LINE_ORDER).toList();
            BigDecimal subtotal =
                    krw(
                            lines.stream()
                                    .map(PurchaseBundle.Line::lineAmountKrw)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            LocalDate delivery =
                    lines.stream()
                            .map(PurchaseBundle.Line::expectedDeliveryDate)
                            .max(LocalDate::compareTo)
                            .orElseThrow();
            orders.add(
                    new PurchaseBundle.Order(
                            entry.getKey(),
                            names.get(entry.getKey()),
                            "KRW",
                            delivery,
                            lines,
                            subtotal));
            total = krw(total.add(subtotal));
        }
        require(same(total, calculation.totalAmount()), "CALCULATION_TOTAL_MISMATCH");
        return new PurchaseBundle(
                orders.isEmpty() ? "NO_PURCHASE_REQUIRED" : "READY",
                planRef,
                version,
                hash,
                sourceHash,
                snapshot.warehouseId(),
                snapshot.asOf(),
                target,
                orders,
                total);
    }

    private static void validateSource(
            Map<String, Map<String, Object>> facts,
            BomPlanner.Item material,
            SupplierSelectionService.SupplierTerm term) {
        require(
                term.active() && term.supplierId() > 0 && term.termId() > 0,
                "INACTIVE_SELECTED_TERM");
        require(
                term.baseUnitsPerBuyUnit() != null
                        && CONVERSIONS.stream().anyMatch(f -> same(f, term.baseUnitsPerBuyUnit())),
                "UNSUPPORTED_CONVERSION_FACTOR");
        var raw = fact(facts, "raw_materials:" + material.id());
        require(
                id(raw, "raw_material_id") == material.id()
                        && text(raw, "unit").equals(material.unit())
                        && material.unit().equals(term.baseUnit()),
                "MATERIAL_SOURCE_MISMATCH");
        text(raw, "name");
        var source = fact(facts, "supplier_material_terms:" + term.termId());
        require(
                id(source, "supplier_material_term_id") == term.termId()
                        && id(source, "raw_material_id") == material.id()
                        && id(source, "supplier_id") == term.supplierId()
                        && Boolean.TRUE.equals(source.get("is_active"))
                        && Boolean.TRUE.equals(source.get("supplier_active"))
                        && text(source, "purchase_unit").equals(term.buyUnit())
                        && "KRW".equals(term.currency())
                        && text(source, "currency").equals(term.currency())
                        && same(
                                number(source, "base_quantity_per_purchase_unit"),
                                term.baseUnitsPerBuyUnit())
                        && same(number(source, "unit_price"), term.unitPrice())
                        && same(
                                number(source, "minimum_order_quantity"),
                                term.minimumOrderQuantity())
                        && same(number(source, "order_multiple"), term.orderMultiple())
                        && id(source, "lead_time_days") == term.leadTimeDays()
                        && Objects.equals(
                                source.get("valid_from"),
                                term.validFrom() == null ? null : term.validFrom().toString())
                        && source.containsKey("valid_to")
                        && Objects.equals(
                                source.get("valid_to"),
                                term.validUntil() == null ? null : term.validUntil().toString()),
                "TERM_SOURCE_MISMATCH");
        text(source, "supplier_name");
        require(source.get("required_cert_types") instanceof List<?>, "TERM_SOURCE_MISMATCH");
        require(
                term.requiredCertificateTypes() != null
                        && new HashSet<>((List<?>) source.get("required_cert_types"))
                                .equals(term.requiredCertificateTypes()),
                "TERM_SOURCE_MISMATCH");
        var buy = fact(facts, "measurement_units:" + term.buyUnit());
        var base = fact(facts, "measurement_units:" + term.baseUnit());
        require(
                text(buy, "code").equals(term.buyUnit())
                        && text(base, "code").equals(term.baseUnit())
                        && text(buy, "dimension").equals(text(base, "dimension"))
                        && number(buy, "to_canonical_factor").signum() > 0
                        && number(base, "to_canonical_factor").signum() > 0
                        && same(
                                term.baseUnitsPerBuyUnit()
                                        .multiply(number(base, "to_canonical_factor")),
                                number(buy, "to_canonical_factor")),
                "UNIT_SOURCE_CONVERSION_MISMATCH");
    }

    private static BigDecimal basePrice(BigDecimal buyPrice, BigDecimal factor) {
        require(buyPrice != null && buyPrice.signum() >= 0, "INVALID_BASE_UNIT_PRICE");
        BigDecimal value;
        try {
            value = buyPrice.divide(factor, 9, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException unsupported) {
            throw invalid("UNREPRESENTABLE_BASE_UNIT_PRICE");
        }
        require(value.compareTo(MAX_BASE_PRICE) <= 0, "BASE_UNIT_PRICE_OVERFLOW");
        return value;
    }

    private static BigDecimal krw(BigDecimal value) {
        require(
                value.signum() >= 0
                        && value.compareTo(MAX_KRW) <= 0
                        && value.stripTrailingZeros().scale() <= 0,
                "KRW_AMOUNT_OVERFLOW");
        return value.setScale(0, RoundingMode.UNNECESSARY);
    }

    private static boolean sameCandidate(
            SupplierSelectionService.Candidate expected,
            SupplierSelectionService.Candidate supplied) {
        return expected != null
                && expected.supplierId() == supplied.supplierId()
                && expected.termId() == supplied.termId()
                && Objects.equals(expected.buyUnit(), supplied.buyUnit())
                && Objects.equals(expected.baseUnit(), supplied.baseUnit())
                && Objects.equals(expected.currency(), supplied.currency())
                && Objects.equals(expected.expectedArrival(), supplied.expectedArrival())
                && same(expected.purchaseQuantity(), supplied.purchaseQuantity())
                && same(expected.suppliedBaseQuantity(), supplied.suppliedBaseQuantity())
                && same(expected.unitPrice(), supplied.unitPrice())
                && same(expected.totalAmount(), supplied.totalAmount());
    }

    private static Map<String, Object> fact(
            Map<String, Map<String, Object>> facts, String reference) {
        var row = facts.get(reference);
        require(row != null, "MISSING_SOURCE_FACT: " + reference);
        return row;
    }

    private static String text(Map<String, Object> row, String key) {
        require(
                row.get(key) instanceof String && !((String) row.get(key)).isBlank(),
                "INVALID_SOURCE_FIELD: " + key);
        return (String) row.get(key);
    }

    private static BigDecimal number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        require(value instanceof Number, "INVALID_SOURCE_FIELD: " + key);
        try {
            return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
        } catch (NumberFormatException invalid) {
            throw invalid("INVALID_SOURCE_FIELD: " + key);
        }
    }

    private static long id(Map<String, Object> row, String key) {
        try {
            return number(row, key).longValueExact();
        } catch (ArithmeticException invalid) {
            throw invalid("INVALID_SOURCE_FIELD: " + key);
        }
    }

    private static boolean same(BigDecimal first, BigDecimal second) {
        return first != null && second != null && first.compareTo(second) == 0;
    }

    private static boolean digest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw invalid(reason);
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(reason);
    }

    private record LineIdentity(long material, long term, LocalDate needDate) {}
}

package com.mulinocoreano.backend.planning;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;

@Service
public class SupplierSelectionService {

    private static final BigDecimal MAX_STORED_DECIMAL = new BigDecimal("999999999999.999999");

    // These are the initial measurement_units catalogue entries. Unknown units fail closed.
    private static final Map<String, Unit> UNITS = Map.of(
            "KG", new Unit("MASS", BigDecimal.ONE),
            "G", new Unit("MASS", new BigDecimal("0.001")),
            "L", new Unit("VOLUME", BigDecimal.ONE),
            "ML", new Unit("VOLUME", new BigDecimal("0.001")),
            "EA", new Unit("COUNT", BigDecimal.ONE),
            "CASE", new Unit("PACKAGE", BigDecimal.ONE));

    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
            .comparing(Candidate::totalAmount)
            .thenComparing(Candidate::expectedArrival)
            .thenComparingLong(Candidate::supplierId)
            .thenComparingLong(Candidate::termId);

    /**
     * Computes one purchase candidate per term, in purchase units. Eligibility and money are
     * calculated solely from this snapshot; this service never reads or writes ERP state.
     */
    public SelectionResult select(LocalDate asOf, LocalDate needDate, BigDecimal requiredBaseQuantity,
                                  String materialBaseUnit, List<SupplierTerm> terms,
                                  List<Certificate> certificates) {
        validateInput(asOf, needDate, requiredBaseQuantity, materialBaseUnit, terms, certificates);
        if (requiredBaseQuantity.signum() == 0) {
            return new SelectionResult(null, List.of(), List.of(), List.of(), "NO_PURCHASE_REQUIRED");
        }

        List<Candidate> feasible = new ArrayList<>();
        List<Rejection> rejected = new ArrayList<>();
        List<Warning> warnings = new ArrayList<>();
        for (SupplierTerm term : terms) {
            Set<String> reasons = new TreeSet<>();
            List<Warning> termWarnings = new ArrayList<>();
            LocalDate arrival = validateTerm(term, asOf, needDate, materialBaseUnit, reasons);
            validateCertificates(term, asOf, arrival, certificates, reasons, termWarnings);
            warnings.addAll(termWarnings);
            if (!reasons.isEmpty()) {
                rejected.add(new Rejection(term.supplierId(), term.termId(), List.copyOf(reasons)));
                continue;
            }

            // Divide directly by the size of one order multiple. No intermediate quantity rounding
            // can understate a shortage or push an exact multiple into the following one.
            BigDecimal neededMultiples = requiredBaseQuantity.divide(
                    term.baseUnitsPerBuyUnit().multiply(term.orderMultiple()), 0, RoundingMode.CEILING);
            BigDecimal minimumMultiples = term.minimumOrderQuantity()
                    .divide(term.orderMultiple(), 0, RoundingMode.CEILING);
            BigDecimal quantity = neededMultiples.max(minimumMultiples).multiply(term.orderMultiple());
            BigDecimal suppliedBaseQuantity = quantity.multiply(term.baseUnitsPerBuyUnit());
            validateStoredQuantity(quantity, reasons);
            validateStoredQuantity(suppliedBaseQuantity, reasons);
            if (!reasons.isEmpty()) {
                rejected.add(new Rejection(term.supplierId(), term.termId(), List.copyOf(reasons)));
                continue;
            }
            BigDecimal total = quantity.multiply(term.unitPrice()).setScale(0, RoundingMode.HALF_UP);
            feasible.add(new Candidate(term.supplierId(), term.termId(), term.buyUnit(), quantity,
                    term.baseUnit(), suppliedBaseQuantity, term.unitPrice(),
                    term.currency(), total, arrival, termWarnings));
        }

        feasible.sort(CANDIDATE_ORDER);
        rejected.sort(Comparator.comparingLong(Rejection::supplierId).thenComparingLong(Rejection::termId));
        warnings.sort(Comparator.comparingLong(Warning::supplierId).thenComparingLong(Warning::termId)
                .thenComparing(Warning::code).thenComparing(Warning::detail));
        Candidate chosen = feasible.isEmpty() ? null : feasible.getFirst();
        return new SelectionResult(chosen, feasible, rejected, warnings,
                chosen == null ? "NO_ELIGIBLE_SUPPLIER" : "SELECTED");
    }

    private static void validateInput(LocalDate asOf, LocalDate needDate, BigDecimal quantity,
                                      String materialBaseUnit, List<SupplierTerm> terms,
                                      List<Certificate> certificates) {
        if (asOf == null || needDate == null || !nonNegative(quantity)
                || materialBaseUnit == null || materialBaseUnit.isBlank()
                || terms == null || certificates == null
                || terms.stream().anyMatch(term -> term == null)
                || certificates.stream().anyMatch(certificate -> certificate == null)) {
            throw new IllegalArgumentException("Dates, a non-negative shortage, base unit and complete lists are required");
        }
        Set<String> reasons = new TreeSet<>();
        validateStoredQuantity(quantity, reasons);
        if (!reasons.isEmpty()) {
            throw new IllegalArgumentException(String.join(",", reasons));
        }
    }

    private static LocalDate validateTerm(SupplierTerm term, LocalDate asOf, LocalDate needDate,
                                          String materialBaseUnit, Set<String> reasons) {
        if (!term.active()) {
            reasons.add("INACTIVE_SUPPLIER_TERM");
        }
        if (term.supplierId() <= 0 || term.termId() <= 0) {
            reasons.add("INVALID_SUPPLIER_TERM_ID");
        }
        if (!positive(term.baseUnitsPerBuyUnit())) {
            reasons.add("INVALID_CONVERSION_FACTOR");
        }
        if (!nonNegative(term.unitPrice())) {
            reasons.add("INVALID_UNIT_PRICE");
        }
        if (!nonNegative(term.minimumOrderQuantity())) {
            reasons.add("INVALID_MINIMUM_ORDER_QUANTITY");
        }
        if (!positive(term.orderMultiple())) {
            reasons.add("INVALID_ORDER_MULTIPLE");
        }
        validateStoredQuantity(term.minimumOrderQuantity(), reasons);
        validateStoredQuantity(term.orderMultiple(), reasons);
        validateStoredDecimal(term.unitPrice(), "UNIT_PRICE_OVERFLOW", "UNSUPPORTED_UNIT_PRICE_PRECISION", reasons);
        if (!"KRW".equals(term.currency())) {
            reasons.add("UNSUPPORTED_CURRENCY");
        }
        validateUnits(term, materialBaseUnit, reasons);

        LocalDate arrival = null;
        if (term.leadTimeDays() < 0) {
            reasons.add("INVALID_LEAD_TIME");
        } else {
            try {
                arrival = asOf.plusDays(term.leadTimeDays());
                if (arrival.isAfter(needDate)) {
                    reasons.add("ARRIVES_AFTER_NEED_DATE");
                }
            } catch (DateTimeException exception) {
                reasons.add("INVALID_LEAD_TIME");
            }
        }

        if (term.validFrom() == null
                || (term.validUntil() != null && term.validUntil().isBefore(term.validFrom()))) {
            reasons.add("INVALID_TERM_VALIDITY");
        }
        if (term.validFrom() != null && term.validFrom().isAfter(asOf)) {
            reasons.add("TERM_NOT_YET_VALID");
        }
        if (term.validUntil() != null) {
            if (term.validUntil().isBefore(asOf)) {
                reasons.add("TERM_EXPIRED");
            }
            if (arrival != null && term.validUntil().isBefore(arrival)) {
                reasons.add("TERM_EXPIRES_BEFORE_ARRIVAL");
            }
        }
        return arrival;
    }

    private static void validateUnits(SupplierTerm term, String materialBaseUnit, Set<String> reasons) {
        Unit material = UNITS.get(materialBaseUnit);
        Unit buy = term.buyUnit() == null ? null : UNITS.get(term.buyUnit());
        Unit base = term.baseUnit() == null ? null : UNITS.get(term.baseUnit());
        if (material == null) {
            reasons.add("UNKNOWN_MATERIAL_BASE_UNIT");
        }
        if (buy == null) {
            reasons.add("UNKNOWN_BUY_UNIT");
        }
        if (base == null) {
            reasons.add("UNKNOWN_BASE_UNIT");
        }
        if (!materialBaseUnit.equals(term.baseUnit())) {
            reasons.add("MATERIAL_BASE_UNIT_MISMATCH");
        }
        if (buy != null && base != null) {
            if (!buy.dimension().equals(base.dimension())) {
                reasons.add("UNIT_DIMENSION_MISMATCH");
            } else if (positive(term.baseUnitsPerBuyUnit())
                    && term.baseUnitsPerBuyUnit().multiply(base.canonicalFactor())
                    .compareTo(buy.canonicalFactor()) != 0) {
                reasons.add("CONVERSION_FACTOR_MISMATCH");
            }
        }
    }

    private static void validateCertificates(SupplierTerm term, LocalDate asOf, LocalDate arrival,
                                             List<Certificate> certificates, Set<String> reasons,
                                             List<Warning> warnings) {
        if (term.requiredCertificateTypes() == null || term.requiredCertificateTypes().stream()
                .anyMatch(type -> type == null || type.isBlank())) {
            reasons.add("INVALID_CERTIFICATE_REQUIREMENTS");
            return;
        }
        for (String type : new TreeSet<>(term.requiredCertificateTypes())) {
            List<Certificate> matching = certificates.stream()
                    .filter(certificate -> certificate.supplierId() == term.supplierId()
                            && type.equals(certificate.type())).toList();
            if (matching.isEmpty()) {
                reasons.add("MISSING_CERTIFICATE:" + type);
                continue;
            }
            List<Certificate> valid = new ArrayList<>();
            Set<String> certificateReasons = new TreeSet<>();
            for (Certificate certificate : matching) {
                Set<String> failures = certificateFailures(certificate, asOf, arrival);
                if (failures.isEmpty()) {
                    valid.add(certificate);
                } else {
                    failures.forEach(reason -> certificateReasons.add(reason + ":" + type));
                }
            }
            if (valid.isEmpty()) {
                reasons.addAll(certificateReasons);
                continue;
            }

            // A current renewal may replace an old certificate. Prefer the longest usable validity
            // so a superseded document does not create an unnecessary expiry warning.
            valid.sort(Comparator.comparing(Certificate::expiresOn).reversed()
                    .thenComparing(Certificate::issuedOn).thenComparing(Certificate::sourceRef));
            Certificate selected = valid.getFirst();
            if (ChronoUnit.DAYS.between(asOf, selected.expiresOn()) <= 30) {
                warnings.add(new Warning(term.supplierId(), term.termId(),
                        "CERTIFICATE_EXPIRES_WITHIN_30_DAYS",
                        type + ":" + selected.sourceRef() + ":" + selected.expiresOn()));
            }
        }
    }

    private static Set<String> certificateFailures(Certificate certificate, LocalDate asOf, LocalDate arrival) {
        Set<String> failures = new TreeSet<>();
        if (certificate.issuedOn() == null || certificate.expiresOn() == null
                || certificate.expiresOn().isBefore(certificate.issuedOn())
                || certificate.sourceRef() == null || certificate.sourceRef().isBlank()) {
            failures.add("INVALID_CERTIFICATE");
            return failures;
        }
        if (certificate.issuedOn().isAfter(asOf)) {
            failures.add("CERTIFICATE_NOT_YET_ISSUED");
        }
        if (certificate.expiresOn().isBefore(asOf)) {
            failures.add("CERTIFICATE_EXPIRED");
        }
        if (arrival != null && certificate.expiresOn().isBefore(arrival)) {
            failures.add("CERTIFICATE_EXPIRES_BEFORE_ARRIVAL");
        }
        return failures;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean nonNegative(BigDecimal value) {
        return value != null && value.signum() >= 0;
    }

    private static void validateStoredQuantity(BigDecimal value, Set<String> reasons) {
        validateStoredDecimal(value, "QUANTITY_OVERFLOW", "UNSUPPORTED_QUANTITY_PRECISION", reasons);
    }

    private static void validateStoredDecimal(BigDecimal value, String overflowCode, String precisionCode,
                                             Set<String> reasons) {
        if (value == null) {
            return;
        }
        if (value.abs().compareTo(MAX_STORED_DECIMAL) > 0) {
            reasons.add(overflowCode);
        }
        // Reject values PostgreSQL would round when writing NUMERIC(18,6), including converted
        // base quantities. Rounding here could change the chosen amount or its conversion identity.
        if (value.stripTrailingZeros().scale() > 6) {
            reasons.add(precisionCode);
        }
    }

    public record SupplierTerm(long supplierId, long termId, boolean active, String buyUnit,
                               BigDecimal baseUnitsPerBuyUnit, String baseUnit, BigDecimal unitPrice,
                               String currency, BigDecimal minimumOrderQuantity, BigDecimal orderMultiple,
                               int leadTimeDays, LocalDate validFrom, LocalDate validUntil,
                               Set<String> requiredCertificateTypes) {
        public SupplierTerm {
            if (requiredCertificateTypes != null) {
                requiredCertificateTypes = Collections.unmodifiableSet(new LinkedHashSet<>(requiredCertificateTypes));
            }
        }
    }

    public record Certificate(long supplierId, String type, LocalDate issuedOn,
                              LocalDate expiresOn, String sourceRef) { }

    public record Candidate(long supplierId, long termId, String buyUnit, BigDecimal purchaseQuantity,
                            String baseUnit, BigDecimal suppliedBaseQuantity, BigDecimal unitPrice,
                            String currency, BigDecimal totalAmount, LocalDate expectedArrival,
                            List<Warning> warnings) {
        public Candidate {
            warnings = List.copyOf(warnings);
        }
    }

    public record Rejection(long supplierId, long termId, List<String> reasons) {
        public Rejection {
            reasons = List.copyOf(reasons);
        }
    }

    public record Warning(long supplierId, long termId, String code, String detail) { }

    public record SelectionResult(Candidate chosen, List<Candidate> feasibleCandidates,
                                  List<Rejection> rejectedCandidates, List<Warning> warnings,
                                  String reason) {
        public SelectionResult {
            feasibleCandidates = List.copyOf(feasibleCandidates);
            rejectedCandidates = List.copyOf(rejectedCandidates);
            warnings = List.copyOf(warnings);
        }
    }

    private record Unit(String dimension, BigDecimal canonicalFactor) { }
}

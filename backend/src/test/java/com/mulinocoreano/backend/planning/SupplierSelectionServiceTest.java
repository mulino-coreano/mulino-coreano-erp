package com.mulinocoreano.backend.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.mulinocoreano.backend.planning.SupplierSelectionService.Certificate;
import com.mulinocoreano.backend.planning.SupplierSelectionService.SupplierTerm;

class SupplierSelectionServiceTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);
    private final SupplierSelectionService service = new SupplierSelectionService();

    @Test
    void cheaperUnitPriceLosesWhenMinimumOrderMakesTotalMoreExpensive() {
        var result = service.select(AS_OF, AS_OF.plusDays(5), decimal("10"), "KG", List.of(
                term(2, 20, "10", "100", "1", 1),
                term(1, 10, "15", "0", "1", 2)), List.of());

        assertThat(result.reason()).isEqualTo("SELECTED");
        assertThat(result.chosen().supplierId()).isEqualTo(1);
        assertThat(result.chosen().purchaseQuantity()).isEqualByComparingTo("10");
        assertThat(result.chosen().totalAmount()).isEqualByComparingTo("150");
        assertThat(result.feasibleCandidates()).extracting(candidate -> candidate.supplierId())
                .containsExactly(1L, 2L);
        assertThat(result.rejectedCandidates()).isEmpty();
    }

    @Test
    void fractionalConversionRoundsPurchaseMultipleUpWithoutEarlyMoneyRounding() {
        var fractional = new SupplierTerm(1, 10, true, "G", decimal("0.001"), "KG",
                decimal("100.333333"), "KRW", decimal("0"), decimal("0.25"),
                2, AS_OF, null, Set.of());

        var result = service.select(AS_OF, AS_OF.plusDays(2), decimal("0.001401"), "KG",
                List.of(fractional), List.of());

        assertThat(result.chosen().purchaseQuantity()).isEqualByComparingTo("1.50");
        assertThat(result.chosen().suppliedBaseQuantity()).isEqualByComparingTo("0.0015");
        assertThat(result.chosen().totalAmount()).isEqualByComparingTo("150");
        assertThat(result.chosen().expectedArrival()).isEqualTo(AS_OF.plusDays(2));
    }

    @Test
    void roundsFinalWonHalfUp() {
        var result = service.select(AS_OF, AS_OF, decimal("1"), "KG",
                List.of(term(1, 10, "100.5", "0", "1", 0)), List.of());

        assertThat(result.chosen().totalAmount()).isEqualTo(decimal("101"));
    }

    @Test
    void roundsMinimumOrderUpToTheNextMultiple() {
        var result = service.select(AS_OF, AS_OF, decimal("1"), "KG",
                List.of(term(1, 10, "10", "2.1", "0.4", 0)), List.of());

        assertThat(result.chosen().purchaseQuantity()).isEqualByComparingTo("2.4");
        assertThat(result.chosen().totalAmount()).isEqualByComparingTo("24");
    }

    @ParameterizedTest(name = "{0} to {1}")
    @MethodSource("validUnitConversions")
    void acceptsOnlyDocumentedConversions(String buyUnit, String baseUnit, String factor,
                                          String shortage, String expectedBuy, String expectedBase) {
        var term = new SupplierTerm(1, 1, true, buyUnit, decimal(factor), baseUnit, decimal("10"),
                "KRW", decimal("0"), decimal("1"), 0, AS_OF, null, Set.of());
        var result = service.select(AS_OF, AS_OF, decimal(shortage), baseUnit, List.of(term), List.of());

        assertThat(result.chosen()).isNotNull();
        assertThat(result.chosen().purchaseQuantity()).isEqualByComparingTo(expectedBuy);
        assertThat(result.chosen().suppliedBaseQuantity()).isEqualByComparingTo(expectedBase);
    }

    @Test
    void ranksEqualCostByArrivalThenSupplierThenTermRegardlessOfInputOrder() {
        var terms = List.of(term(2, 3, "10", "0", "1", 1),
                term(1, 2, "10", "0", "1", 1),
                term(1, 1, "10", "0", "1", 1),
                term(3, 4, "10", "0", "1", 0));
        var result = service.select(AS_OF, AS_OF.plusDays(5), decimal("1"), "KG", terms, List.of());

        assertThat(result.chosen().supplierId()).isEqualTo(3);
        assertThat(result.feasibleCandidates()).extracting(candidate -> candidate.termId())
                .containsExactly(4L, 1L, 2L, 3L);
    }

    @Test
    void rejectsLateAndInactiveTermsAndRetainsEveryApplicableReason() {
        var inactive = new SupplierTerm(2, 20, false, "KG", decimal("1"), "KG",
                decimal("1"), "KRW", decimal("0"), decimal("1"),
                8, AS_OF.minusDays(1), AS_OF.plusDays(1), Set.of("HACCP"));
        var result = service.select(AS_OF, AS_OF.plusDays(2), decimal("1"), "KG",
                List.of(inactive, term(1, 10, "1", "0", "1", 3)), List.of());

        assertThat(result.chosen()).isNull();
        assertThat(result.reason()).isEqualTo("NO_ELIGIBLE_SUPPLIER");
        assertThat(result.rejectedCandidates()).extracting(rejection -> rejection.supplierId())
                .containsExactly(1L, 2L);
        assertThat(result.rejectedCandidates().get(1).reasons()).contains(
                "INACTIVE_SUPPLIER_TERM", "ARRIVES_AFTER_NEED_DATE", "TERM_EXPIRES_BEFORE_ARRIVAL",
                "MISSING_CERTIFICATE:HACCP");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableCertificates")
    void rejectsRequiredCertificateProblems(String reason, List<Certificate> certificates) {
        var result = service.select(AS_OF, AS_OF.plusDays(5), decimal("1"), "KG",
                List.of(certifiedTerm()), certificates);

        assertThat(result.chosen()).isNull();
        assertThat(result.rejectedCandidates()).singleElement()
                .satisfies(rejection -> assertThat(rejection.reasons()).contains(reason + ":HACCP"));
    }

    @Test
    void acceptsCertificatesIssuedTodayAndExpiringOnArrivalAndWarnsAtThirtyDayBoundary() {
        var certificate = new Certificate(1, "HACCP", AS_OF, AS_OF.plusDays(30), "CERT-1");
        var term = new SupplierTerm(1, 10, true, "KG", decimal("1"), "KG", decimal("1"),
                "KRW", decimal("0"), decimal("1"), 30, AS_OF, AS_OF.plusDays(30), Set.of("HACCP"));
        var result = service.select(AS_OF, AS_OF.plusDays(30), decimal("1"), "KG",
                List.of(term), List.of(certificate));

        assertThat(result.chosen()).isNotNull();
        assertThat(result.chosen().warnings()).singleElement()
                .satisfies(warning -> assertThat(warning.code()).isEqualTo("CERTIFICATE_EXPIRES_WITHIN_30_DAYS"));
        assertThat(result.warnings()).isEqualTo(result.chosen().warnings());
    }

    @Test
    void validRenewalCanSatisfyARequirementDespiteOldExpiredOrFutureCertificate() {
        var certificates = List.of(
                new Certificate(1, "HACCP", AS_OF.minusDays(60), AS_OF.minusDays(1), "OLD"),
                new Certificate(1, "HACCP", AS_OF.plusDays(1), AS_OF.plusDays(90), "FUTURE"),
                new Certificate(1, "HACCP", AS_OF.minusDays(1), AS_OF.plusDays(31), "VALID"));
        var result = service.select(AS_OF, AS_OF.plusDays(5), decimal("1"), "KG",
                List.of(certifiedTerm()), certificates);

        assertThat(result.chosen()).isNotNull();
        assertThat(result.rejectedCandidates()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void certificateFromAnotherSupplierDoesNotSatisfyRequirement() {
        var certificate = new Certificate(2, "HACCP", AS_OF.minusDays(1), AS_OF.plusDays(60), "OTHER");
        var result = service.select(AS_OF, AS_OF.plusDays(5), decimal("1"), "KG",
                List.of(certifiedTerm()), List.of(certificate));

        assertThat(result.chosen()).isNull();
        assertThat(result.rejectedCandidates().getFirst().reasons()).contains("MISSING_CERTIFICATE:HACCP");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTerms")
    void invalidTermIsAnExplainedRejection(String reason, SupplierTerm invalid) {
        var result = service.select(AS_OF, AS_OF.plusDays(5), decimal("1"), "KG",
                List.of(invalid), List.of());

        assertThat(result.chosen()).isNull();
        assertThat(result.rejectedCandidates().getFirst().reasons()).contains(reason);
    }

    @Test
    void unknownMaterialBaseUnitFailsClosed() {
        var result = service.select(AS_OF, AS_OF, decimal("1"), "SCOOP",
                List.of(term(1, 1, "1", "0", "1", 0)), List.of());

        assertThat(result.chosen()).isNull();
        assertThat(result.rejectedCandidates().getFirst().reasons()).contains("UNKNOWN_MATERIAL_BASE_UNIT");
    }

    @Test
    void zeroShortageDoesNotApplyMinimumOrderOrRequireSupplier() {
        var result = service.select(AS_OF, AS_OF, decimal("0"), "KG",
                List.of(term(1, 1, "10", "100", "1", 0)), List.of());

        assertThat(result.chosen()).isNull();
        assertThat(result.reason()).isEqualTo("NO_PURCHASE_REQUIRED");
        assertThat(result.feasibleCandidates()).isEmpty();
        assertThat(result.rejectedCandidates()).isEmpty();
    }

    @Test
    void noTermsReturnsExplicitNoEligibleSupplier() {
        var result = service.select(AS_OF, AS_OF, decimal("1"), "KG", List.of(), List.of());

        assertThat(result.chosen()).isNull();
        assertThat(result.reason()).isEqualTo("NO_ELIGIBLE_SUPPLIER");
        assertThat(result.feasibleCandidates()).isEmpty();
    }

    @Test
    void rejectsNegativeShortageBeforeConstructingAPurchase() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.select(AS_OF, AS_OF,
                decimal("-0.1"), "KG", List.of(), List.of()));
    }

    @Test
    void rejectsConvertedBaseQuantityOverflowAndChoosesPersistableSupplier() {
        var overflowing = new SupplierTerm(1, 1, true, "KG", decimal("1000"), "G", decimal("0"),
                "KRW", decimal("1000000000"), decimal("1"), 0, AS_OF, null, Set.of());
        var usable = new SupplierTerm(2, 2, true, "G", decimal("1"), "G", decimal("1"),
                "KRW", decimal("0"), decimal("1"), 0, AS_OF, null, Set.of());

        var result = service.select(AS_OF, AS_OF, decimal("1"), "G", List.of(overflowing, usable), List.of());

        assertThat(result.chosen().supplierId()).isEqualTo(2);
        assertThat(result.feasibleCandidates()).hasSize(1);
        assertThat(result.rejectedCandidates()).singleElement()
                .satisfies(rejection -> assertThat(rejection.reasons()).contains("QUANTITY_OVERFLOW"));
    }

    @Test
    void rejectsConvertedPurchaseQuantityOverflowAndChoosesPersistableSupplier() {
        var overflowing = new SupplierTerm(1, 1, true, "G", decimal("0.001"), "KG", decimal("0"),
                "KRW", decimal("0"), decimal("1"), 0, AS_OF, null, Set.of());

        var result = service.select(AS_OF, AS_OF, decimal("1000000000"), "KG",
                List.of(overflowing, term(2, 2, "1", "0", "1", 0)), List.of());

        assertThat(result.chosen().supplierId()).isEqualTo(2);
        assertThat(result.chosen().purchaseQuantity()).isEqualByComparingTo("1000000000");
        assertThat(result.rejectedCandidates()).singleElement()
                .satisfies(rejection -> assertThat(rejection.reasons()).contains("QUANTITY_OVERFLOW"));
    }

    @Test
    void rejectsBaseConversionThatWouldLosePrecisionWhenPersisted() {
        var imprecise = new SupplierTerm(1, 1, true, "G", decimal("0.001"), "KG", decimal("0"),
                "KRW", decimal("0"), decimal("0.000003"), 0, AS_OF, null, Set.of());

        var result = service.select(AS_OF, AS_OF, decimal("0.000001"), "KG",
                List.of(imprecise, term(2, 2, "1", "0", "0.000001", 0)), List.of());

        assertThat(result.chosen().supplierId()).isEqualTo(2);
        assertThat(result.chosen().suppliedBaseQuantity()).isEqualByComparingTo("0.000001");
        assertThat(result.rejectedCandidates()).singleElement()
                .satisfies(rejection -> assertThat(rejection.reasons()).contains("UNSUPPORTED_QUANTITY_PRECISION"));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("unpersistableTerms")
    void rejectsUnpersistableTermNumericsAndStillConsidersOtherSuppliers(String reason, SupplierTerm invalid) {
        var result = service.select(AS_OF, AS_OF, decimal("1"), "KG",
                List.of(invalid, term(2, 2, "1", "0", "1", 0)), List.of());

        assertThat(result.chosen().supplierId()).isEqualTo(2);
        assertThat(result.feasibleCandidates()).hasSize(1);
        assertThat(result.rejectedCandidates()).singleElement()
                .satisfies(rejection -> assertThat(rejection.reasons()).contains(reason));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("unpersistableShortages")
    void rejectsUnpersistableShortageBeforeSupplierSelection(String reason, String shortage) {
        assertThatIllegalArgumentException().isThrownBy(() -> service.select(AS_OF, AS_OF,
                        decimal(shortage), "KG", List.of(term(1, 1, "0", "0", "1", 0)), List.of()))
                .withMessageContaining(reason);
    }

    @Test
    void acceptsMaximumQuantityAndPriceWithoutImposingQuantityLimitOnTotalMoney() {
        var boundary = term(1, 1, "999999999999.999999", "0", "0.000001", 0);

        var result = service.select(AS_OF, AS_OF, decimal("999999999999.999999"), "KG",
                List.of(boundary), List.of());

        assertThat(result.chosen().purchaseQuantity()).isEqualByComparingTo("999999999999.999999");
        assertThat(result.chosen().suppliedBaseQuantity()).isEqualByComparingTo("999999999999.999999");
        assertThat(result.chosen().unitPrice()).isEqualByComparingTo("999999999999.999999");
        assertThat(result.chosen().totalAmount()).isEqualByComparingTo("999999999999999998000000");
    }

    @Test
    void acceptsRedundantTrailingZeroesWithoutCallingThemPrecisionLoss() {
        var valid = term(1, 1, "0.1000000", "0.0000000", "0.0000010", 0);

        var result = service.select(AS_OF, AS_OF, decimal("0.0000010"), "KG", List.of(valid), List.of());

        assertThat(result.chosen().purchaseQuantity()).isEqualByComparingTo("0.000001");
        assertThat(result.rejectedCandidates()).isEmpty();
    }

    private static Stream<Arguments> unpersistableTerms() {
        return Stream.of(
                Arguments.of("QUANTITY_OVERFLOW", term(1, 1, "0", "1000000000000", "1", 0)),
                Arguments.of("QUANTITY_OVERFLOW", term(1, 1, "0", "0", "1000000000000", 0)),
                Arguments.of("UNSUPPORTED_QUANTITY_PRECISION", term(1, 1, "0", "0.0000001", "1", 0)),
                Arguments.of("UNSUPPORTED_QUANTITY_PRECISION", term(1, 1, "0", "0", "0.0000001", 0)),
                Arguments.of("UNIT_PRICE_OVERFLOW", term(1, 1, "1000000000000", "0", "1", 0)),
                Arguments.of("UNSUPPORTED_UNIT_PRICE_PRECISION", term(1, 1, "0.0000001", "0", "1", 0)));
    }

    private static Stream<Arguments> unpersistableShortages() {
        return Stream.of(
                Arguments.of("QUANTITY_OVERFLOW", "1000000000000"),
                Arguments.of("UNSUPPORTED_QUANTITY_PRECISION", "0.0000001"));
    }

    private static Stream<Arguments> unusableCertificates() {
        return Stream.of(
                Arguments.of("MISSING_CERTIFICATE", List.of()),
                Arguments.of("CERTIFICATE_NOT_YET_ISSUED", List.of(new Certificate(
                        1, "HACCP", AS_OF.plusDays(1), AS_OF.plusDays(60), "FUTURE"))),
                Arguments.of("CERTIFICATE_EXPIRED", List.of(new Certificate(
                        1, "HACCP", AS_OF.minusDays(60), AS_OF.minusDays(1), "EXPIRED"))),
                Arguments.of("CERTIFICATE_EXPIRES_BEFORE_ARRIVAL", List.of(new Certificate(
                        1, "HACCP", AS_OF.minusDays(60), AS_OF.plusDays(1), "TOO-SOON"))),
                Arguments.of("INVALID_CERTIFICATE", List.of(new Certificate(
                        1, "HACCP", AS_OF, AS_OF.minusDays(1), "REVERSED"))),
                Arguments.of("INVALID_CERTIFICATE", List.of(new Certificate(
                        1, "HACCP", null, AS_OF.plusDays(60), "MISSING-DATE"))),
                Arguments.of("INVALID_CERTIFICATE", List.of(new Certificate(
                        1, "HACCP", AS_OF.minusDays(1), AS_OF.plusDays(60), " "))));
    }

    private static Stream<Arguments> validUnitConversions() {
        return Stream.of(
                Arguments.of("KG", "G", "1000", "1001", "2", "2000"),
                Arguments.of("L", "ML", "1000", "250", "1", "1000"),
                Arguments.of("ML", "L", "0.001", "0.0005", "1", "0.001"),
                Arguments.of("EA", "EA", "1", "1.1", "2", "2"),
                Arguments.of("CASE", "CASE", "1", "2", "2", "2"));
    }

    private static Stream<Arguments> invalidTerms() {
        return Stream.of(
                invalid("INVALID_CONVERSION_FACTOR", "KG", "0", "KG", "1", "KRW", "0", "1", 0, AS_OF, null),
                invalid("INVALID_CONVERSION_FACTOR", "KG", "-1", "KG", "1", "KRW", "0", "1", 0, AS_OF, null),
                invalid("INVALID_UNIT_PRICE", "KG", "1", "KG", "-1", "KRW", "0", "1", 0, AS_OF, null),
                invalid("INVALID_MINIMUM_ORDER_QUANTITY", "KG", "1", "KG", "1", "KRW", "-1", "1", 0, AS_OF, null),
                invalid("INVALID_ORDER_MULTIPLE", "KG", "1", "KG", "1", "KRW", "0", "0", 0, AS_OF, null),
                invalid("INVALID_LEAD_TIME", "KG", "1", "KG", "1", "KRW", "0", "1", -1, AS_OF, null),
                invalid("UNSUPPORTED_CURRENCY", "KG", "1", "KG", "1", "USD", "0", "1", 0, AS_OF, null),
                invalid("UNKNOWN_BUY_UNIT", "BAG", "1", "KG", "1", "KRW", "0", "1", 0, AS_OF, null),
                invalid("UNKNOWN_BASE_UNIT", "KG", "1", "SCOOP", "1", "KRW", "0", "1", 0, AS_OF, null),
                invalid("UNIT_DIMENSION_MISMATCH", "L", "1", "KG", "1", "KRW", "0", "1", 0, AS_OF, null),
                invalid("UNIT_DIMENSION_MISMATCH", "CASE", "1", "KG", "1", "KRW", "0", "1", 0, AS_OF, null),
                invalid("MATERIAL_BASE_UNIT_MISMATCH", "G", "1", "G", "1", "KRW", "0", "1", 0, AS_OF, null),
                invalid("CONVERSION_FACTOR_MISMATCH", "G", "1", "KG", "1", "KRW", "0", "1", 0, AS_OF, null),
                invalid("INVALID_TERM_VALIDITY", "KG", "1", "KG", "1", "KRW", "0", "1", 0, AS_OF, AS_OF.minusDays(1)),
                invalid("TERM_NOT_YET_VALID", "KG", "1", "KG", "1", "KRW", "0", "1", 0, AS_OF.plusDays(1), null),
                invalid("TERM_EXPIRED", "KG", "1", "KG", "1", "KRW", "0", "1", 0, AS_OF.minusDays(60), AS_OF.minusDays(1)));
    }

    private static Arguments invalid(String reason, String buyUnit, String factor, String baseUnit,
                                     String price, String currency, String minimum, String multiple,
                                     int lead, LocalDate from, LocalDate until) {
        return Arguments.of(reason, new SupplierTerm(1, 1, true, buyUnit, decimal(factor), baseUnit,
                decimal(price), currency, decimal(minimum), decimal(multiple), lead, from, until, Set.of()));
    }

    private static SupplierTerm certifiedTerm() {
        return new SupplierTerm(1, 10, true, "KG", decimal("1"), "KG", decimal("10"),
                "KRW", decimal("0"), decimal("1"), 2, AS_OF, null, Set.of("HACCP"));
    }

    private static SupplierTerm term(long supplierId, long termId, String price, String minimum,
                                     String multiple, int lead) {
        return new SupplierTerm(supplierId, termId, true, "KG", decimal("1"), "KG", decimal(price),
                "KRW", decimal(minimum), decimal(multiple), lead, AS_OF, null, Set.of());
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}

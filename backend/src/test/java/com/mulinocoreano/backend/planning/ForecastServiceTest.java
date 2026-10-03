package com.mulinocoreano.backend.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.mulinocoreano.backend.planning.ForecastService.HistoricalOrder;
import com.mulinocoreano.backend.planning.ForecastService.OpenOrder;

class ForecastServiceTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 7); // Monday
    private final ForecastService service = new ForecastService();

    @Test
    void zeroDemandUsesOnlyDeclaredHistoryCoverageAndPreservesItsBoundary() {
        var result = service.forecast(AS_OF, AS_OF.minusDays(40), 3, 7, List.of(), List.of());

        assertThat(result).isNotNull();
        assertThat(result.historyStart()).isEqualTo(AS_OF.minusDays(40));
        assertThat(result.historyWindowStart()).isEqualTo(AS_OF.minusDays(40));
        assertThat(result.historyEnd()).isEqualTo(AS_OF.minusDays(1));
        assertThat(result.observedDays()).isEqualTo(40);
        assertThat(result.dailyDemand()).extracting(ForecastService.DailyDemand::date)
                .containsExactly(AS_OF, AS_OF.plusDays(1), AS_OF.plusDays(2));
        assertThat(result.dailyDemand()).allSatisfy(day -> {
            assertThat(day.forecastQuantity()).isEqualByComparingTo("0");
            assertThat(day.confirmedRemainingQuantity()).isEqualByComparingTo("0");
            assertThat(day.effectiveDemand()).isEqualByComparingTo("0");
        });
        assertThat(result.safetyQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void weekdayProfileIncludesMissingDaysButExcludesUnconfirmedAndFutureOrders() {
        var result = service.forecast(AS_OF, AS_OF.minusDays(28), 2, 7, List.of(
                history(7, "CONFIRMED", "12", "order:1"),
                history(14, "SHIPPED", "8", "order:2"),
                history(21, "PENDING", "1000", "pending"),
                history(28, "CANCELLED", "1000", "cancelled"),
                history(6, "SHIPPED", "4", "order:3"),
                history(0, "CONFIRMED", "1000", "today"),
                history(-1, "CONFIRMED", "1000", "future")), List.of());

        assertThat(result).isNotNull();
        assertThat(result.weekdayForecast().get(DayOfWeek.MONDAY)).isEqualByComparingTo("5");
        assertThat(result.weekdayForecast().get(DayOfWeek.TUESDAY)).isEqualByComparingTo("1");
        assertThat(result.dailyDemand().getFirst().forecastQuantity()).isEqualByComparingTo("5");
        assertThat(result.dailyDemand().get(1).forecastQuantity()).isEqualByComparingTo("1");
        assertThat(result.sourceRefs()).containsExactly("order:1", "order:2", "order:3");
    }

    @Test
    void capsHistoryAt56DaysAndDoesNotTreatEarlierRowsAsObservedDemand() {
        var result = service.forecast(AS_OF, AS_OF.minusDays(90), 1, 0, List.of(
                history(56, "SHIPPED", "8", "first-in-window"),
                history(63, "SHIPPED", "8000", "too-old")), List.of());

        assertThat(result).isNotNull();
        assertThat(result.historyStart()).isEqualTo(AS_OF.minusDays(90));
        assertThat(result.historyWindowStart()).isEqualTo(AS_OF.minusDays(56));
        assertThat(result.observedDays()).isEqualTo(56);
        assertThat(result.dailyDemand().getFirst().forecastQuantity()).isEqualByComparingTo("1");
        assertThat(result.safetyQuantity()).isEqualByComparingTo("0");
        assertThat(result.sourceRefs()).containsExactly("first-in-window");
    }

    @Test
    void coverageOf29DaysUsesEachWeekdaysOwnObservationCount() {
        var result = service.forecast(AS_OF, AS_OF.minusDays(29), 7, 0, List.of(
                history(1, "SHIPPED", "10", "sunday"),
                history(7, "SHIPPED", "12", "monday")), List.of());

        assertThat(result).isNotNull();
        assertThat(result.weekdayForecast().get(DayOfWeek.SUNDAY)).isEqualByComparingTo("2");
        assertThat(result.weekdayForecast().get(DayOfWeek.MONDAY)).isEqualByComparingTo("3");
    }

    @Test
    void safetyUsesFullWeeklyProfileEvenWhenHorizonContainsOnlyOneWeekday() {
        var result = service.forecast(AS_OF, AS_OF.minusDays(28), 1, 7,
                List.of(history(6, "SHIPPED", "196", "tuesday-only")), List.of());

        assertThat(result).isNotNull();
        assertThat(result.dailyDemand().getFirst().forecastQuantity()).isEqualByComparingTo("0");
        assertThat(result.dailyMean()).isEqualByComparingTo("7");
        assertThat(result.safetyQuantity()).isEqualByComparingTo("49");
    }

    @Test
    void roundsFractionalDemandAndSafetyUpAtSixDecimalPlaces() {
        var result = service.forecast(AS_OF, AS_OF.minusDays(35), 1, 1,
                List.of(history(7, "SHIPPED", "0.000001", "fractional")), List.of());

        assertThat(result).isNotNull();
        assertThat(result.weekdayForecast().get(DayOfWeek.MONDAY)).isEqualByComparingTo("0.000001");
        assertThat(result.dailyMean()).isEqualByComparingTo("0.000001");
        assertThat(result.safetyQuantity()).isEqualByComparingTo("0.000001");
    }

    @Test
    void subtractsShipmentsAndConsumesForecastWithDailyMaximumInsteadOfAddingIt() {
        var result = service.forecast(AS_OF, AS_OF.minusDays(28), 2, 0,
                List.of(history(7, "SHIPPED", "40", "history")), List.of(
                        open(AS_OF, "CONFIRMED", "8", "3", "open:1"),
                        open(AS_OF.minusDays(3), "CONFIRMED", "4", "0", "overdue"),
                        open(AS_OF.plusDays(1), "CONFIRMED", "20", "5", "open:2"),
                        open(AS_OF.plusDays(2), "CONFIRMED", "100", "0", "outside"),
                        open(null, "CONFIRMED", "10", "10", "already-shipped"),
                        open(null, "PENDING", "500", "0", "pending"),
                        open(AS_OF, "CANCELLED", "500", "0", "cancelled"),
                        open(AS_OF, "SHIPPED", "500", "500", "shipped")));

        assertThat(result).isNotNull();
        assertThat(result.dailyDemand().getFirst().confirmedRemainingQuantity()).isEqualByComparingTo("9");
        assertThat(result.dailyDemand().getFirst().effectiveDemand()).isEqualByComparingTo("10");
        assertThat(result.dailyDemand().get(1).confirmedRemainingQuantity()).isEqualByComparingTo("15");
        assertThat(result.dailyDemand().get(1).effectiveDemand()).isEqualByComparingTo("15");
        assertThat(result.sourceRefs()).containsExactly("history", "open:1", "open:2", "overdue");
    }

    @Test
    void retainsExactDecimalConfirmedQuantities() {
        var result = service.forecast(AS_OF, AS_OF.minusDays(28), 1, 0, List.of(), List.of(
                open(AS_OF, "CONFIRMED", "0.3", "0.1", "decimal")));

        assertThat(result).isNotNull();
        assertThat(result.dailyDemand().getFirst().effectiveDemand()).isEqualByComparingTo("0.2");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 27, -1})
    void refusesInsufficientOrFutureHistoryCoverage(int historyDays) {
        assertThatIllegalArgumentException().isThrownBy(() -> service.forecast(
                AS_OF, AS_OF.minusDays(historyDays), 1, 7, List.of(), List.of()));
    }

    @ParameterizedTest
    @CsvSource({"0,7", "91,7", "-1,7", "1,-1", "1,91"})
    void rejectsUnsupportedHorizonOrSafetyDays(int horizonDays, int safetyDays) {
        assertThatIllegalArgumentException().isThrownBy(() -> service.forecast(
                AS_OF, AS_OF.minusDays(28), horizonDays, safetyDays, List.of(), List.of()));
    }

    @Test
    void refusesRemainingConfirmedOrderWithoutDueDate() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.forecast(
                AS_OF, AS_OF.minusDays(28), 1, 7, List.of(),
                List.of(open(null, "CONFIRMED", "2", "1", "missing-date"))))
                .withMessageContaining("MISSING_ORDER_DUE_DATE");
    }

    @ParameterizedTest
    @CsvSource({"-1,0", "1,-1", "1,2"})
    void refusesNegativeOrOverShippedOrders(String quantity, String shipped) {
        assertThatIllegalArgumentException().isThrownBy(() -> service.forecast(
                AS_OF, AS_OF.minusDays(28), 1, 7, List.of(),
                List.of(open(AS_OF, "CONFIRMED", quantity, shipped, "invalid"))));
    }

    @Test
    void refusesUnknownStatusesAndNegativeHistoryEvenIfOutsideWindow() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.forecast(
                AS_OF, AS_OF.minusDays(28), 1, 7,
                List.of(history(40, "SHIPPED", "-1", "negative")), List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> service.forecast(
                AS_OF, AS_OF.minusDays(28), 1, 7,
                List.of(history(1, "UNKNOWN", "1", "unknown")), List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> service.forecast(
                AS_OF, AS_OF.minusDays(28), 1, 7, List.of(),
                List.of(open(AS_OF, "UNKNOWN", "1", "0", "unknown"))));
    }

    @Test
    void refusesDuplicateRowsInsteadOfCountingDemandTwice() {
        var row = history(7, "SHIPPED", "10", "duplicate");
        assertThatIllegalArgumentException().isThrownBy(() -> service.forecast(
                AS_OF, AS_OF.minusDays(28), 1, 7, List.of(row, row), List.of()));
        var open = open(AS_OF, "CONFIRMED", "10", "0", "duplicate");
        assertThatIllegalArgumentException().isThrownBy(() -> service.forecast(
                AS_OF, AS_OF.minusDays(28), 1, 7, List.of(), List.of(open, open)));
    }

    @Test
    void sameOrderCanContributeHistoryAndRemainingDemandWithoutSummingThem() {
        var result = service.forecast(AS_OF, AS_OF.minusDays(28), 1, 0,
                List.of(history(7, "CONFIRMED", "40", "order:1")),
                List.of(open(AS_OF, "CONFIRMED", "40", "25", "order:1")));

        assertThat(result).isNotNull();
        assertThat(result.dailyDemand().getFirst().effectiveDemand()).isEqualByComparingTo("15");
        assertThat(result.sourceRefs()).containsExactly("order:1");
    }

    @Test
    void rejectsMissingInputDatesAndNullRows() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.forecast(
                null, AS_OF.minusDays(28), 1, 7, List.of(), List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> service.forecast(
                AS_OF, null, 1, 7, List.of(), List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> service.forecast(
                AS_OF, AS_OF.minusDays(28), 1, 7,
                List.of(new HistoricalOrder(null, "SHIPPED", BigDecimal.ONE, "missing")), List.of()));
        var nullRow = new ArrayList<HistoricalOrder>();
        nullRow.add(null);
        assertThatIllegalArgumentException().isThrownBy(() -> service.forecast(
                AS_OF, AS_OF.minusDays(28), 1, 7, nullRow, List.of()));
    }

    private static HistoricalOrder history(int daysAgo, String status, String quantity, String ref) {
        return new HistoricalOrder(AS_OF.minusDays(daysAgo), status, new BigDecimal(quantity), ref);
    }

    private static OpenOrder open(LocalDate dueDate, String status, String quantity, String shipped, String ref) {
        return new OpenOrder(dueDate, status, new BigDecimal(quantity), new BigDecimal(shipped), ref);
    }
}

package com.mulinocoreano.backend.planning;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;

@Service
public class ForecastService {

    private static final int QUANTITY_SCALE = 6;
    private static final Set<String> ORDER_STATUSES = Set.of("PENDING", "CONFIRMED", "SHIPPED", "CANCELLED");

    /**
     * Forecasts one product from a declared, continuous history coverage ending just before {@code asOf}.
     * Missing order days inside that coverage count as zero; dates before collection began do not.
     * Weekday means and the full-week daily mean use six decimal places with CEILING rounding;
     * safety quantity multiplies that conservative daily mean by the requested safety days.
     * Callers must partition rows by product and supply unique order-line source references.
     */
    public ForecastResult forecast(LocalDate asOf, LocalDate historyStart, int horizonDays, int safetyDays,
                                   List<HistoricalOrder> history, List<OpenOrder> openOrders) {
        require(asOf != null && historyStart != null && history != null && openOrders != null,
                "INVALID_FORECAST_REQUEST: dates and order lists are required");
        require(horizonDays >= 1 && horizonDays <= 90 && safetyDays >= 0 && safetyDays <= 90,
                "INVALID_FORECAST_REQUEST: horizon must be 1..90 days and safety must be 0..90 days");
        require(historyStart.isBefore(asOf), "INSUFFICIENT_HISTORY: collection must start before asOf");

        LocalDate historyWindowStart = historyStart.isAfter(asOf.minusDays(56))
                ? historyStart : asOf.minusDays(56);
        int observedDays = Math.toIntExact(ChronoUnit.DAYS.between(historyWindowStart, asOf));
        require(observedDays >= 28, "INSUFFICIENT_HISTORY: at least 28 observed calendar days are required");

        EnumMap<DayOfWeek, BigDecimal> weekdayTotals = new EnumMap<>(DayOfWeek.class);
        EnumMap<DayOfWeek, Integer> weekdayCounts = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek weekday : DayOfWeek.values()) {
            weekdayTotals.put(weekday, BigDecimal.ZERO);
            weekdayCounts.put(weekday, 0);
        }
        for (LocalDate day = historyWindowStart; day.isBefore(asOf); day = day.plusDays(1)) {
            weekdayCounts.merge(day.getDayOfWeek(), 1, Integer::sum);
        }

        Set<String> sourceRefs = new TreeSet<>();
        Set<String> historicalRows = new HashSet<>();
        for (HistoricalOrder row : history) {
            require(row != null && row.orderDate() != null, "INVALID_HISTORICAL_ORDER: order date is required");
            validateOrder(row.status(), row.quantity(), row.sourceRef(), "INVALID_HISTORICAL_ORDER");
            require(historicalRows.add(row.sourceRef()), "DUPLICATE_SOURCE_REFERENCE: " + row.sourceRef());
            if (row.orderDate().isBefore(historyWindowStart) || !row.orderDate().isBefore(asOf)
                    || !Set.of("CONFIRMED", "SHIPPED").contains(row.status())) {
                continue;
            }
            weekdayTotals.merge(row.orderDate().getDayOfWeek(), row.quantity(), BigDecimal::add);
            sourceRefs.add(row.sourceRef());
        }

        EnumMap<DayOfWeek, BigDecimal> weekdayForecast = new EnumMap<>(DayOfWeek.class);
        BigDecimal weeklyQuantity = BigDecimal.ZERO;
        for (DayOfWeek weekday : DayOfWeek.values()) {
            BigDecimal forecast = weekdayTotals.get(weekday).divide(BigDecimal.valueOf(weekdayCounts.get(weekday)),
                    QUANTITY_SCALE, RoundingMode.CEILING);
            weekdayForecast.put(weekday, forecast);
            weeklyQuantity = weeklyQuantity.add(forecast);
        }

        LocalDate horizonEndExclusive = asOf.plusDays(horizonDays);
        Map<LocalDate, BigDecimal> remainingByDate = new HashMap<>();
        Set<String> openRows = new HashSet<>();
        for (OpenOrder row : openOrders) {
            require(row != null, "INVALID_OPEN_ORDER: order is required");
            validateOrder(row.status(), row.quantity(), row.sourceRef(), "INVALID_OPEN_ORDER");
            require(row.shippedQuantity() != null && row.shippedQuantity().signum() >= 0
                            && row.shippedQuantity().compareTo(row.quantity()) <= 0,
                    "INVALID_OPEN_ORDER: shipped quantity must be between zero and ordered quantity");
            require(openRows.add(row.sourceRef()), "DUPLICATE_SOURCE_REFERENCE: " + row.sourceRef());
            BigDecimal remaining = row.quantity().subtract(row.shippedQuantity());
            if (!row.status().equals("CONFIRMED") || remaining.signum() == 0) {
                continue;
            }
            require(row.dueDate() != null, "MISSING_ORDER_DUE_DATE: " + row.sourceRef());
            LocalDate needDate = row.dueDate().isBefore(asOf) ? asOf : row.dueDate();
            if (needDate.isBefore(horizonEndExclusive)) {
                remainingByDate.merge(needDate, remaining, BigDecimal::add);
                sourceRefs.add(row.sourceRef());
            }
        }

        List<DailyDemand> dailyDemand = new ArrayList<>();
        for (int offset = 0; offset < horizonDays; offset++) {
            LocalDate date = asOf.plusDays(offset);
            BigDecimal forecast = weekdayForecast.get(date.getDayOfWeek());
            BigDecimal remaining = remainingByDate.getOrDefault(date, BigDecimal.ZERO);
            dailyDemand.add(new DailyDemand(date, forecast, remaining, forecast.max(remaining)));
        }
        BigDecimal dailyMean = weeklyQuantity.divide(BigDecimal.valueOf(7), QUANTITY_SCALE, RoundingMode.CEILING);
        BigDecimal safetyQuantity = dailyMean.multiply(BigDecimal.valueOf(safetyDays))
                .setScale(QUANTITY_SCALE, RoundingMode.CEILING);
        return new ForecastResult(asOf, historyStart, historyWindowStart, asOf.minusDays(1), observedDays,
                horizonDays, safetyDays, dailyDemand, weekdayForecast, dailyMean, safetyQuantity,
                List.copyOf(sourceRefs));
    }

    private static void validateOrder(String status, BigDecimal quantity, String sourceRef, String code) {
        require(status != null && ORDER_STATUSES.contains(status), code + ": unknown order status");
        require(quantity != null && quantity.signum() >= 0, code + ": nonnegative quantity is required");
        require(sourceRef != null && !sourceRef.isBlank(), code + ": source reference is required");
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }

    public record HistoricalOrder(LocalDate orderDate, String status, BigDecimal quantity, String sourceRef) { }

    public record OpenOrder(LocalDate dueDate, String status, BigDecimal quantity,
                            BigDecimal shippedQuantity, String sourceRef) { }

    public record DailyDemand(LocalDate date, BigDecimal forecastQuantity,
                              BigDecimal confirmedRemainingQuantity, BigDecimal effectiveDemand) { }

    public record ForecastResult(LocalDate asOf, LocalDate historyStart, LocalDate historyWindowStart,
                                 LocalDate historyEnd, int observedDays, int horizonDays, int safetyDays,
                                 List<DailyDemand> dailyDemand, Map<DayOfWeek, BigDecimal> weekdayForecast,
                                 BigDecimal dailyMean, BigDecimal safetyQuantity, List<String> sourceRefs) {
        public ForecastResult {
            dailyDemand = List.copyOf(dailyDemand);
            weekdayForecast = Collections.unmodifiableMap(new EnumMap<>(weekdayForecast));
            sourceRefs = List.copyOf(sourceRefs);
        }
    }
}

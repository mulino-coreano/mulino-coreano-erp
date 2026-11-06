package com.mulinocoreano.backend.planning;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalJsonTest {
    private final CanonicalJson json = new CanonicalJson(new ObjectMapper());

    @Test
    void sortsNestedKeysWithoutRoundingDecimalValues() {
        var nested = new LinkedHashMap<String, Object>();
        nested.put("z", new BigDecimal("9007199254740993.123456"));
        nested.put("a", new BigDecimal("0.00000001"));
        var value = Map.of("b", List.of(nested), "a", "한글\n\"");
        assertThat(json.write(value)).isEqualTo("{\"a\":\"한글\\n\\\"\",\"b\":[{\"a\":0.00000001,\"z\":9007199254740993.123456}]}");
        assertThat(json.sha256(value)).isEqualTo(json.sha256(Map.of("a", "한글\n\"", "b", List.of(Map.of(
                "a", new BigDecimal("0.00000001"), "z", new BigDecimal("9007199254740993.123456"))))));
        assertThat(json.sha256(value)).matches("[0-9a-f]{64}");
    }

    @Test
    void supportsImmutableRecordsAndInstants() {
        assertThat(json.write(new Example(Instant.parse("2026-09-05T00:00:00Z"), new BigDecimal("16500.000000"))))
                .isEqualTo("{\"amount\":16500,\"asOf\":\"2026-09-05T00:00:00Z\"}");
        assertThat(json.sha256(Map.of("amount", new BigDecimal("16500.0"))))
                .isEqualTo(json.sha256(Map.of("amount", new BigDecimal("16500.000000"))));
    }

    @Test
    void rejectsNonFiniteNumbersRatherThanHashingAString() {
        assertThatThrownBy(() -> json.write(Map.of("value", Double.NaN))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> json.write(Map.of("value", Double.POSITIVE_INFINITY))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storedJsonRoundTripRetainsEveryDecimalDigit() {
        var amount = new BigDecimal("9007199254740993.123456");
        var node = json.readTree(json.write(Map.of("amount", amount)));
        assertThat(node.path("amount").decimalValue()).isEqualByComparingTo(amount);
        assertThat(json.sha256(node)).isEqualTo(json.sha256(Map.of("amount", amount)));
    }

    record Example(Instant asOf, BigDecimal amount) {}
}

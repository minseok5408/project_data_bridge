package io.databridge.ims;

import static io.databridge.ims.ProductionCalculator.Action.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.*;
import java.util.*;
import java.util.stream.Stream;

class ProductionCalculatorTest {
    private static final Instant BASE = Instant.parse("2026-09-22T00:00:00Z");
    private static final Duration GAP = Duration.ofSeconds(15);

    static ModbusProduction.Reading reading(int second, int status, long counter) {
        return new ModbusProduction.Reading(
                1, 3, BASE.plusSeconds(second), status, counter, 1, "HIGH_LOW");
    }

    static ProductionCalculator.State state(int status, long counter, boolean running) {
        return new ProductionCalculator.State(reading(0, status, counter), BASE, running);
    }

    static Arguments scenario(
            String name,
            ProductionCalculator.State state,
            ModbusProduction.Reading next,
            int receivedSecond,
            ProductionCalculator.Action action,
            String interruption,
            long quantity) {
        return Arguments.of(
                name,
                state,
                next,
                BASE.plusSeconds(receivedSecond),
                new ProductionCalculator.Transition(action, interruption, quantity));
    }

    static Stream<Arguments> transitions() {
        return Stream.of(
                scenario("첫 생산 관측은 기준값", null, reading(0, 1, 100), 0, START, null, 0),
                scenario("첫 중지 관측은 상태만 저장", null, reading(0, 0, 100), 0, NONE, null, 0),
                scenario("알 수 없는 첫 상태는 구간을 만들지 않음", null, reading(0, 99, 100), 0, NONE, null, 0),
                scenario("누적값 증가", state(1, 100, true), reading(5, 1, 115), 5, INCREMENT, null, 15),
                scenario(
                        "최종 누적값을 포함하고 중지",
                        state(1, 100, true),
                        reading(5, 0, 118),
                        5,
                        COMPLETE,
                        null,
                        18),
                scenario(
                        "중지 시 초기화는 기존 수량 보존",
                        state(1, 100, true),
                        reading(5, 0, 0),
                        5,
                        COMPLETE,
                        null,
                        0),
                scenario(
                        "휴식 후 누적값 유지", state(0, 118, false), reading(5, 1, 122), 5, START, null, 4),
                scenario(
                        "휴식 중 초기화 후 첫 수량 포함",
                        state(0, 118, false),
                        reading(5, 1, 3),
                        5,
                        START,
                        null,
                        3),
                scenario(
                        "생산 중 초기화는 새 구간",
                        state(1, 100, true),
                        reading(5, 1, 3),
                        5,
                        START,
                        "counter_reset",
                        3),
                scenario(
                        "관측 공백은 누적 증가를 합산하지 않음",
                        state(1, 100, true),
                        reading(16, 1, 500),
                        16,
                        START,
                        "connection_gap",
                        0),
                scenario(
                        "수신 공백도 구간 분리",
                        state(1, 100, true),
                        reading(5, 1, 500),
                        16,
                        START,
                        "connection_gap",
                        0),
                scenario(
                        "공백 기준의 경계는 허용",
                        state(1, 100, true),
                        reading(15, 1, 120),
                        15,
                        INCREMENT,
                        null,
                        20),
                scenario(
                        "공백 뒤 중지는 종료 시각을 추측하지 않음",
                        state(1, 100, true),
                        reading(16, 0, 118),
                        16,
                        NONE,
                        "connection_gap",
                        0),
                scenario(
                        "알 수 없는 상태는 생산 구간 중단",
                        state(1, 100, true),
                        reading(5, 99, 500),
                        5,
                        NONE,
                        "invalid_status",
                        0),
                scenario(
                        "상태 복구 후 기준값부터 시작",
                        state(-1, 500, false),
                        reading(5, 1, 800),
                        5,
                        START,
                        null,
                        0),
                scenario("중지 상태 유지", state(0, 118, false), reading(5, 0, 118), 5, NONE, null, 0),
                scenario(
                        "같은 시각은 반영하지 않음",
                        state(1, 100, true),
                        reading(0, 0, 0),
                        5,
                        IGNORE_STALE,
                        null,
                        0),
                scenario(
                        "역순 시각은 반영하지 않음",
                        state(1, 100, true),
                        reading(-1, 1, 200),
                        5,
                        IGNORE_STALE,
                        null,
                        0),
                scenario(
                        "하위 16비트 넘침은 초기화가 아님",
                        state(1, 65530, true),
                        reading(5, 1, 65540),
                        5,
                        INCREMENT,
                        null,
                        10),
                scenario(
                        "부호 없는 32비트 범위",
                        state(1, 2147483648L, true),
                        reading(5, 1, 4294967295L),
                        5,
                        INCREMENT,
                        null,
                        2147483647L));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transitions")
    void calculatesProductionWithoutDatabase(
            String name,
            ProductionCalculator.State state,
            ModbusProduction.Reading next,
            Instant received,
            ProductionCalculator.Transition expected) {
        assertEquals(expected, ProductionCalculator.calculate(state, next, received, GAP));
    }

    @Test
    void mappingChangeTakesPriorityOverCounterReset() {
        var previous = state(1, 100, true);
        for (var next :
                List.of(
                        new ModbusProduction.Reading(
                                1, 3, BASE.plusSeconds(5), 1, 3, 2, "HIGH_LOW"),
                        new ModbusProduction.Reading(
                                1, 3, BASE.plusSeconds(5), 1, 3, 1, "LOW_HIGH"))) {
            assertEquals(
                    new ProductionCalculator.Transition(START, "mapping_changed", 0),
                    ProductionCalculator.calculate(previous, next, BASE.plusSeconds(5), GAP));
        }
    }

    @Test
    void subsecondOrderingSurvivesClockRollback() {
        var first =
                new ModbusProduction.Reading(
                        1, 3, BASE.plusNanos(900_000_000), 1, 15, 1, "HIGH_LOW");
        var state = new ProductionCalculator.State(first, BASE, true);
        var old =
                new ModbusProduction.Reading(
                        1, 3, BASE.plusNanos(500_000_000), 0, 0, 1, "HIGH_LOW");
        assertEquals(
                IGNORE_STALE,
                ProductionCalculator.calculate(state, old, BASE.plusSeconds(1), GAP).action());
        var newer = new ModbusProduction.Reading(1, 3, BASE.plusSeconds(1), 1, 20, 1, "HIGH_LOW");
        assertEquals(
                new ProductionCalculator.Transition(INCREMENT, null, 5),
                ProductionCalculator.calculate(state, newer, BASE.plusSeconds(1), GAP));
    }

    static ModbusData packet(String... times) {
        var blocks = new ArrayList<ModbusData.Data>();
        for (int i = 0; i < times.length; i++)
            blocks.add(new ModbusData.Data(times[i], i + 1, 3, 1, List.of(1, 0, 100), 1));
        return new ModbusData(UUID.randomUUID().toString(), "company", "ND01", blocks);
    }

    @Test
    void futureTimeLimitAllowsBoundaryAndOldImportsButRejectsOutlierWithItsField() {
        var clock = Clock.fixed(BASE, ZoneOffset.UTC);
        assertDoesNotThrow(
                () ->
                        ProductionCalculator.validateObservationTimes(
                                packet(
                                        BASE.minusSeconds(86400).toString(),
                                        BASE.plusSeconds(60).toString()),
                                clock.instant()));
        var error =
                assertThrows(
                        ValidationException.class,
                        () ->
                                ProductionCalculator.validateObservationTimes(
                                        packet(
                                                BASE.toString(),
                                                BASE.plusSeconds(60).plusNanos(1).toString()),
                                        clock.instant()));
        assertEquals("FUTURE_OBSERVATION", error.code());
        assertEquals("datas[1].time", error.field());
        assertThrows(
                ValidationException.class,
                () ->
                        ProductionCalculator.validateObservationTimes(
                                packet("+100000-01-01T00:00:00Z"), clock.instant()));
    }

    @Test
    void bothWordOrdersDecodeUnsignedCounters() {
        for (String order : List.of("HIGH_LOW", "LOW_HIGH")) {
            var values =
                    order.equals("HIGH_LOW") ? List.of(1, 65535, 32768) : List.of(1, 32768, 65535);
            var packet =
                    new ModbusData(
                            UUID.randomUUID().toString(),
                            "company",
                            "ND01",
                            List.of(
                                    new ModbusData.Data(
                                            BASE.toString(), 1, 3, 1, values, 1, order)));
            assertEquals(4294934528L, ModbusProduction.decode(packet).getFirst().counter());
        }
    }
}

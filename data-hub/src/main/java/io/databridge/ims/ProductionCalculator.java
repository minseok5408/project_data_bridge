package io.databridge.ims;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

/** SQL이나 시스템 시각에 의존하지 않고 생산 상태의 변경 내용을 계산합니다. */
public final class ProductionCalculator {
    // 작은 시계 오차는 허용하되, 잘못된 PC 시각 하나가 이후 관측을 장시간 막지 않도록 제한합니다.
    public static final Duration MAX_FUTURE_SKEW = Duration.ofSeconds(60);

    public record State(ModbusProduction.Reading reading, Instant receivedAt, boolean running) {}

    public enum Action {
        NONE,
        START,
        INCREMENT,
        COMPLETE,
        IGNORE_STALE
    }

    public record Transition(Action action, String interruption, long quantity) {
        public boolean applied() {
            return action != Action.IGNORE_STALE;
        }
    }

    private ProductionCalculator() {}

    public static void validateObservationTimes(ModbusData packet, Instant receivedAt) {
        Instant latestAllowed = receivedAt.plus(MAX_FUTURE_SKEW);
        for (int i = 0; i < packet.datas().size(); i++) {
            if (OffsetDateTime.parse(packet.datas().get(i).time())
                    .toInstant()
                    .isAfter(latestAllowed)) {
                throw new ValidationException(
                        "FUTURE_OBSERVATION",
                        "datas[" + i + "].time",
                        "Modbus observation exceeds hub time by more than 60 seconds; check the"
                                + " collector clock");
            }
        }
    }

    public static Transition calculate(
            State state, ModbusProduction.Reading next, Instant receivedAt, Duration maxGap) {
        var previous = state == null ? null : state.reading();
        boolean running = state != null && state.running();
        long resetQuantity = 0;
        String interruption = null;
        if (previous != null) {
            if (!next.observed().isAfter(previous.observed())) {
                return new Transition(Action.IGNORE_STALE, null, 0);
            }
            if (!next.sameLayout(previous)) interruption = "mapping_changed";
            else if (Duration.between(previous.observed(), next.observed()).compareTo(maxGap) > 0
                    || Duration.between(state.receivedAt(), receivedAt).compareTo(maxGap) > 0) {
                interruption = "connection_gap";
            } else if (!next.validStatus()) interruption = "invalid_status";
            else if (running && next.status() == 1 && next.counter() < previous.counter()) {
                interruption = "counter_reset";
                resetQuantity = next.counter();
            }
            if (interruption != null) {
                running = false;
                previous = null;
            }
        }
        if (next.validStatus() && next.status() == 1) {
            if (!running) {
                long initial =
                        previous != null && previous.validStatus() && previous.status() == 0
                                ? ModbusProduction.increase(previous.counter(), next.counter())
                                : resetQuantity;
                return new Transition(Action.START, interruption, initial);
            }
            return new Transition(
                    Action.INCREMENT,
                    null,
                    ModbusProduction.increase(previous.counter(), next.counter()));
        }
        if (next.validStatus() && running) {
            long quantity = Math.max(0, next.counter() - previous.counter());
            return new Transition(Action.COMPLETE, null, quantity);
        }
        return new Transition(Action.NONE, interruption, 0);
    }
}

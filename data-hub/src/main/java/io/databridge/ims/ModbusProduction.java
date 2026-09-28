package io.databridge.ims;

import java.time.*;
import java.util.*;

/** 생산 데이터는 상태 레지스터와 그 뒤의 두 레지스터로 구성됩니다. 두 레지스터를 하나의 부호 없는 누적값으로 해석합니다. */
public final class ModbusProduction {
    private ModbusProduction() {}

    public record Reading(
            int unitId,
            int functionCode,
            Instant observed,
            int status,
            long counter,
            int statusAddress,
            String wordOrder) {
        public boolean validStatus() {
            return status == 0 || status == 1;
        }

        public boolean sameLayout(Reading other) {
            return statusAddress == other.statusAddress && wordOrder.equals(other.wordOrder);
        }
    }

    public static List<Reading> decode(ModbusData packet) {
        var readings = new ArrayList<Reading>();
        var streams = new HashSet<String>();
        for (int i = 0; i < packet.datas().size(); i++) {
            var block = packet.datas().get(i);
            if (block.statusAddress() == null) continue;
            int offset = block.statusAddress() - block.startAddress();
            if (offset + 2 >= block.vals().size())
                throw new ValidationException(
                        "datas[" + i + "].vals",
                        "Production block requires status and two counter registers");
            if (!streams.add(block.unitId() + "/" + block.functionCode()))
                throw new ValidationException(
                        "datas", "One production block per unit/function required");
            int first = block.vals().get(offset + 1), second = block.vals().get(offset + 2);
            long counter =
                    block.counterWordOrder().equals("HIGH_LOW")
                            ? combine(first, second)
                            : combine(second, first);
            readings.add(
                    new Reading(
                            block.unitId(),
                            block.functionCode(),
                            OffsetDateTime.parse(block.time()).toInstant(),
                            block.vals().get(offset),
                            counter,
                            block.statusAddress(),
                            block.counterWordOrder()));
        }
        return readings;
    }

    public static long combine(int high, int low) {
        return ((long) high << 16) | (long) low;
    }

    /** 누적값이 줄면 새 계산 기준을 0으로 잡습니다. 32비트 누적값에 16비트 넘침 보정은 적용하지 않습니다. */
    public static long increase(long previous, long current) {
        return current >= previous ? current - previous : current;
    }
}

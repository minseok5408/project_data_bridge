package io.databridge.collector;

import java.time.OffsetDateTime;
import java.util.*;

/** 기존 nodeId/datas/vals 구조에 자동 생성한 전송 ID와 레지스터 구간 정보를 포함합니다. */
public record ModbusData(
        String eventId,
        @com.fasterxml.jackson.annotation.JsonProperty("com_cd") String comCd,
        String nodeId,
        List<Data> datas) {
    public record Data(
            String time,
            int unitId,
            int functionCode,
            int startAddress,
            List<Integer> vals,
            Integer statusAddress,
            String counterWordOrder) {
        public Data(
                String time, int unitId, int functionCode, int startAddress, List<Integer> vals) {
            this(time, unitId, functionCode, startAddress, vals, null, "HIGH_LOW");
        }

        public Data(
                String time,
                int unitId,
                int functionCode,
                int startAddress,
                List<Integer> vals,
                Integer statusAddress) {
            this(time, unitId, functionCode, startAddress, vals, statusAddress, "HIGH_LOW");
        }

        public Data {
            require(time != null, "time required");
            OffsetDateTime.parse(time);
            require(
                    unitId >= 0 && unitId <= 247 && (functionCode == 3 || functionCode == 4),
                    "Invalid Modbus unit/function");
            require(
                    vals != null && !vals.isEmpty() && vals.size() <= 125,
                    "1..125 registers required");
            require(
                    startAddress >= 0 && startAddress <= 65536 - vals.size(),
                    "Invalid register range");
            for (var value : vals)
                require(
                        value != null && value >= 0 && value <= 65535,
                        "Register must be an unsigned 16-bit value");
            require(
                    statusAddress == null
                            || statusAddress >= startAddress
                                    && statusAddress < startAddress + vals.size(),
                    "statusAddress must be inside its block");
            if (counterWordOrder == null) counterWordOrder = "HIGH_LOW";
            require(
                    Set.of("HIGH_LOW", "LOW_HIGH").contains(counterWordOrder),
                    "Invalid counter word order");
            vals = List.copyOf(vals);
        }
    }

    public ModbusData {
        require(eventId != null, "eventId required");
        UUID.fromString(eventId);
        Event.id(comCd);
        Event.id(nodeId);
        require(
                datas != null && !datas.isEmpty() && datas.size() <= 64,
                "1..64 data blocks required");
        Set<String> blocks = new HashSet<>();
        for (var data : datas) {
            require(data != null, "Null data block");
            require(
                    blocks.add(
                            data.unitId
                                    + "/"
                                    + data.functionCode
                                    + "/"
                                    + data.startAddress
                                    + "/"
                                    + data.vals.size()),
                    "Duplicate data block");
        }
        datas = List.copyOf(datas);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}

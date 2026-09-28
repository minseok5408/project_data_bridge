package io.databridge.ims;

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
            Event.timestamp(time, "time");
            require(
                    unitId >= 0 && unitId <= 247,
                    "unitId",
                    "Modbus unit must be between 0 and 247");
            require(
                    functionCode == 3 || functionCode == 4,
                    "functionCode",
                    "Modbus function must be 3 or 4");
            require(
                    vals != null && !vals.isEmpty() && vals.size() <= 125,
                    "vals",
                    "1..125 registers required");
            require(
                    startAddress >= 0 && startAddress <= 65536 - vals.size(),
                    "startAddress",
                    "Invalid register range");
            for (var value : vals)
                require(
                        value != null && value >= 0 && value <= 65535,
                        "vals",
                        "Register must be an unsigned 16-bit value");
            require(
                    statusAddress == null
                            || statusAddress >= startAddress
                                    && statusAddress < startAddress + vals.size(),
                    "statusAddress",
                    "statusAddress must be inside its block");
            if (counterWordOrder == null) counterWordOrder = "HIGH_LOW";
            require(
                    Set.of("HIGH_LOW", "LOW_HIGH").contains(counterWordOrder),
                    "counterWordOrder",
                    "Counter word order must be HIGH_LOW or LOW_HIGH");
            vals = List.copyOf(vals);
        }
    }

    public ModbusData {
        Event.eventId(eventId);
        Event.id(comCd, "com_cd");
        Event.id(nodeId, "nodeId");
        require(
                datas != null && !datas.isEmpty() && datas.size() <= 64,
                "datas",
                "1..64 data blocks required");
        Set<String> blocks = new HashSet<>();
        for (var data : datas) {
            require(data != null, "datas", "Null data block");
            require(
                    blocks.add(
                            data.unitId
                                    + "/"
                                    + data.functionCode
                                    + "/"
                                    + data.startAddress
                                    + "/"
                                    + data.vals.size()),
                    "datas",
                    "Duplicate data block");
        }
        datas = List.copyOf(datas);
    }

    private static void require(boolean condition, String field, String message) {
        ValidationException.require(condition, field, message);
    }
}

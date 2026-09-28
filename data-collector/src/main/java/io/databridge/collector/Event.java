package io.databridge.collector;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/** Excel과 Text에서 공통으로 사용하는 JSON 이벤트입니다. 고객사별 처리 규칙은 포함하지 않습니다. */
public record Event(
        String schemaVersion,
        String eventId,
        @com.fasterxml.jackson.annotation.JsonProperty("com_cd") String comCd,
        String sourceId,
        String sourceType,
        String equipmentId,
        String observedAt,
        String collectedAt,
        Map<String, Measurement> measurements,
        Map<String, Object> context,
        Origin origin,
        @com.fasterxml.jackson.annotation.JsonInclude(
                        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                FileData fileData) {
    public record Measurement(Object value, String kind, String unit) {
        public Measurement {
            if (!Set.of("sensor", "counter", "status", "text").contains(kind))
                throw new IllegalArgumentException("Unknown measurement kind");
            if (value != null) {
                switch (kind) {
                    case "sensor", "counter" -> {
                        if (!(value instanceof Number))
                            throw new IllegalArgumentException("Numeric measurement required");
                        var number = new BigDecimal(value.toString());
                        if (kind.equals("counter") && number.signum() < 0)
                            throw new IllegalArgumentException("Negative counter");
                    }
                    case "status" -> {
                        if (!(value instanceof Boolean))
                            throw new IllegalArgumentException("Boolean status required");
                    }
                    case "text" -> {
                        if (!(value instanceof String))
                            throw new IllegalArgumentException("String measurement required");
                    }
                }
            }
        }
    }

    public record Origin(String fileName, Long row) {}

    public record Ack(String eventId, String status) {}

    /** 선택한 항목만 payload 객체에 저장하고, 항목코드와 비고는 별도 컬럼으로 보냅니다. */
    public record FileData(Map<String, Object> payload, String itemCode, String cmnt) {
        public FileData {
            if (payload == null || payload.isEmpty() || payload.size() > 256)
                throw new IllegalArgumentException("1..256 payload entries required");
            payload.forEach(
                    (key, value) -> {
                        if (key == null || key.isBlank() || key.length() > 255)
                            throw new IllegalArgumentException("Invalid payload key");
                        if (value != null && !(value instanceof String))
                            throw new IllegalArgumentException(
                                    "Payload values must be strings or null");
                    });
            if (itemCode != null && itemCode.codePointCount(0, itemCode.length()) > 255)
                throw new IllegalArgumentException("item_code exceeds 255 characters");
            if (cmnt != null
                    && cmnt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65535)
                throw new IllegalArgumentException("cmnt exceeds 65535 UTF-8 bytes");
            payload = Collections.unmodifiableMap(new TreeMap<>(payload));
        }
    }

    public Event(
            String schemaVersion,
            String eventId,
            @com.fasterxml.jackson.annotation.JsonProperty("com_cd") String comCd,
            String sourceId,
            String sourceType,
            String equipmentId,
            String observedAt,
            String collectedAt,
            Map<String, Measurement> measurements,
            Map<String, Object> context,
            Origin origin) {
        this(
                schemaVersion,
                eventId,
                comCd,
                sourceId,
                sourceType,
                equipmentId,
                observedAt,
                collectedAt,
                measurements,
                context,
                origin,
                null);
    }

    public Map<String, Object> filePayload() {
        if (fileData != null) return fileData.payload();
        var payload = new TreeMap<String, Object>();
        measurements.forEach(
                (name, measurement) -> payload.put(name, payloadText(measurement.value())));
        return Collections.unmodifiableMap(payload);
    }

    public static String payloadText(Object value) {
        return value == null
                ? null
                : value instanceof BigDecimal number
                        ? number.stripTrailingZeros().toPlainString()
                        : value.toString();
    }

    public String fileItemCode() {
        return fileData == null ? null : fileData.itemCode();
    }

    public String fileCmnt() {
        return fileData == null ? null : fileData.cmnt();
    }

    public String fileExtension() {
        if (origin == null || origin.fileName() == null) return null;
        String name = origin.fileName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) return null;
        String extension = name.substring(dot + 1);
        return sourceType.equals("text") && extension.equals("txt")
                        || sourceType.equals("excel") && Set.of("xls", "xlsx").contains(extension)
                ? extension
                : null;
    }

    public Event {
        if (!"1.0".equals(schemaVersion))
            throw new IllegalArgumentException("Unsupported schemaVersion");
        UUID.fromString(Objects.requireNonNull(eventId));
        id(comCd);
        id(sourceId);
        id(equipmentId);
        if (!Set.of("excel", "text").contains(sourceType))
            throw new IllegalArgumentException("Unsupported sourceType");
        OffsetDateTime.parse(observedAt);
        OffsetDateTime.parse(collectedAt);
        if (measurements == null || measurements.isEmpty() || measurements.size() > 256)
            throw new IllegalArgumentException("1..256 measurements required");
        measurements.forEach(
                (key, value) -> {
                    id(key);
                    Objects.requireNonNull(value);
                });
        measurements = Collections.unmodifiableMap(new TreeMap<>(measurements));
        context = context == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(context));
        context.values()
                .forEach(
                        v -> {
                            if (v != null
                                    && !(v instanceof String
                                            || v instanceof Number
                                            || v instanceof Boolean))
                                throw new IllegalArgumentException(
                                        "Context values must be scalars");
                        });
        if (origin != null && origin.row() != null && origin.row() < 1)
            throw new IllegalArgumentException("Invalid origin row");
    }

    public static void id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}"))
            throw new IllegalArgumentException("Invalid identifier: " + value);
    }

    public String streamKey() {
        return comCd + "/" + sourceId + "/" + equipmentId;
    }
}

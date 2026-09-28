package io.databridge.ims;

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
            if (kind == null || !Set.of("sensor", "counter", "status", "text").contains(kind))
                throw new ValidationException(
                        "kind", "Measurement kind must be sensor, counter, status or text");
            if (value != null) {
                switch (kind) {
                    case "sensor", "counter" -> {
                        if (!(value instanceof Number))
                            throw new ValidationException("value", "Numeric measurement required");
                        var number = new BigDecimal(value.toString());
                        if (kind.equals("counter") && number.signum() < 0)
                            throw new ValidationException("value", "Counter must not be negative");
                    }
                    case "status" -> {
                        if (!(value instanceof Boolean))
                            throw new ValidationException("value", "Boolean status required");
                    }
                    case "text" -> {
                        if (!(value instanceof String))
                            throw new ValidationException("value", "String measurement required");
                    }
                }
            }
        }
    }

    public record Origin(String fileName, Long row) {
        public Origin {
            if (fileName != null
                    && (fileName.isBlank() || fileName.codePointCount(0, fileName.length()) > 255))
                throw new ValidationException(
                        "fileName", "fileName must contain 1..255 characters");
        }
    }

    public record Ack(String eventId, String status) {}

    /** 선택한 항목만 payload 객체에 저장하고, 항목코드와 비고는 별도 컬럼으로 보냅니다. */
    public record FileData(Map<String, Object> payload, String itemCode, String cmnt) {
        public FileData {
            if (payload == null || payload.isEmpty() || payload.size() > 256)
                throw new ValidationException("payload", "1..256 payload entries required");
            payload.forEach(
                    (key, value) -> {
                        if (key == null || key.isBlank() || key.length() > 255)
                            throw new ValidationException(
                                    "payload", "Payload keys must contain 1..255 characters");
                        if (value != null && !(value instanceof String))
                            throw new ValidationException(
                                    "payload", "Payload values must be strings or null");
                    });
            if (itemCode != null && itemCode.codePointCount(0, itemCode.length()) > 255)
                throw new ValidationException("itemCode", "item_code exceeds 255 characters");
            if (cmnt != null
                    && cmnt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65535)
                throw new ValidationException("cmnt", "cmnt exceeds 65535 UTF-8 bytes");
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

    /** 수집기가 보낸 원본 파일명입니다. complete/error 이동 후 이름은 사용하지 않습니다. */
    public String fileName() {
        return origin == null ? null : origin.fileName();
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
            throw new ValidationException("schemaVersion", "schemaVersion must be 1.0");
        eventId(eventId);
        id(comCd, "com_cd");
        id(sourceId, "sourceId");
        id(equipmentId, "equipmentId");
        if (sourceType == null || !Set.of("excel", "text").contains(sourceType))
            throw new ValidationException("sourceType", "sourceType must be excel or text");
        timestamp(observedAt, "observedAt");
        timestamp(collectedAt, "collectedAt");
        if (measurements == null || measurements.isEmpty() || measurements.size() > 256)
            throw new ValidationException("measurements", "1..256 measurements required");
        measurements.forEach(
                (key, value) -> {
                    id(key, "measurements");
                    ValidationException.require(value != null, "measurements", "Null measurement");
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
                                throw new ValidationException(
                                        "context", "Context values must be scalars");
                        });
        if (origin != null && origin.row() != null && origin.row() < 1)
            throw new ValidationException("origin.row", "Origin row must be positive");
    }

    public static void id(String value) {
        id(value, "identifier");
    }

    public static void id(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}"))
            throw new ValidationException(
                    field,
                    "Identifier must contain 1..80 ASCII letters, digits, underscores, dots or"
                            + " hyphens and start with a letter or digit");
    }

    public static void timestamp(String value, String field) {
        try {
            OffsetDateTime.parse(Objects.requireNonNull(value));
        } catch (java.time.DateTimeException | NullPointerException e) {
            throw new ValidationException(field, "Timestamp must use ISO 8601 with a UTC offset");
        }
    }

    public static void eventId(String value) {
        try {
            UUID.fromString(Objects.requireNonNull(value));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ValidationException("eventId", "eventId must be a UUID");
        }
    }

    public String streamKey() {
        return comCd + "/" + sourceId + "/" + equipmentId;
    }
}

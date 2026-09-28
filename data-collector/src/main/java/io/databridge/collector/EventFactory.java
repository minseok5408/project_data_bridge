package io.databridge.collector;

import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class EventFactory {
    private EventFactory() {}

    public static Event create(
            CollectorConfig config,
            CollectorConfig.Source source,
            Map<String, Object> raw,
            Event.Origin origin) {
        return create(
                config,
                source,
                raw,
                origin,
                UUID.randomUUID().toString(),
                OffsetDateTime.now(ZoneId.of(config.timezone)).toString());
    }

    public static Event createFileRow(
            CollectorConfig config,
            CollectorConfig.Source source,
            Map<String, Object> raw,
            Event.Origin origin,
            String generation,
            String collectedAt) {
        UUID.fromString(generation);
        String rowId =
                UUID.nameUUIDFromBytes(
                                (generation + "/" + origin.row())
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString();
        return create(config, source, raw, origin, rowId, collectedAt);
    }

    public static Event create(
            CollectorConfig config,
            CollectorConfig.Source source,
            Map<String, Object> raw,
            Event.Origin origin,
            String eventId,
            String collectedAt) {
        OffsetDateTime.parse(collectedAt);
        String observed = collectedAt;
        String itemCode = null, cmnt = null;
        var measurements = new TreeMap<String, Event.Measurement>();
        var context = new TreeMap<>(source.context);
        var payload = new LinkedHashMap<String, Object>();
        for (var entry : source.allFields().entrySet()) {
            var f = entry.getValue();
            String target = source.target(entry.getKey());
            if (target.equals("ignore")) continue;
            Object value;
            try {
                value = convert(raw.get(entry.getKey()), f, ZoneId.of(config.timezone));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "Field " + entry.getKey() + ": " + e.getMessage(), e);
            }
            switch (target) {
                case "observed_at" -> observed = (String) value;
                case "item_code" -> itemCode = (String) value;
                case "cmnt" -> cmnt = (String) value;
                case "context" -> context.put(entry.getKey(), value);
                case "payload", "measurement" -> {
                    String name = f.headerName == null ? entry.getKey() : f.headerName.strip();
                    if (payload.containsKey(name))
                        throw new IllegalArgumentException("Duplicate payload key: " + name);
                    // type/scale로 값을 검사·변환한 뒤 {"값":"20"} 형태로 저장합니다.
                    payload.put(name, Event.payloadText(value));
                    String kind =
                            target.equals("measurement")
                                    ? f.kind
                                    : switch (f.type) {
                                        case "str", "datetime" -> "text";
                                        case "bool" -> "status";
                                        default -> f.kind;
                                    };
                    measurements.put(entry.getKey(), new Event.Measurement(value, kind, f.unit));
                }
                default -> throw new IllegalArgumentException("Invalid field target: " + target);
            }
        }
        return new Event(
                "1.0",
                eventId,
                source.comCd,
                source.id,
                source.type,
                source.equipmentId,
                observed,
                collectedAt,
                measurements,
                context,
                origin,
                new Event.FileData(payload, itemCode, cmnt));
    }

    static void checkHeader(String position, String expected, String actual) {
        if (expected != null && !expected.strip().equals(actual.strip()))
            throw new IllegalArgumentException(
                    "헤더 이름 불일치: 위치=" + position + " 설정=" + expected + " 실제=" + actual);
    }

    static Object convert(Object input, CollectorConfig.Field field, ZoneId zone) {
        if (input == null || input.toString().isBlank()) {
            if (field.required) throw new IllegalArgumentException("Required value is empty");
            return null;
        }
        String text = input.toString().strip();
        return switch (field.type) {
            case "str" -> text;
            case "bool" ->
                    switch (text.toLowerCase(Locale.ROOT)) {
                        case "1", "1.0", "true", "y", "yes", "on" -> true;
                        case "0", "0.0", "false", "n", "no", "off" -> false;
                        default -> throw new IllegalArgumentException("Expected boolean value");
                    };
            case "datetime" -> {
                var format =
                        field.datetimeFormat == null
                                ? DateTimeFormatter.ISO_DATE_TIME
                                : DateTimeFormatter.ofPattern(field.datetimeFormat);
                var parsed = format.parseBest(text, OffsetDateTime::from, LocalDateTime::from);
                yield parsed instanceof OffsetDateTime dt
                        ? dt.toString()
                        : ((LocalDateTime) parsed).atZone(zone).toOffsetDateTime().toString();
            }
            default -> {
                BigDecimal number = new BigDecimal(text).multiply(field.scale).add(field.offset);
                if (field.decimals != null)
                    number = number.setScale(field.decimals, RoundingMode.HALF_EVEN);
                yield field.type.equals("int")
                        ? number.longValueExact()
                        : number.stripTrailingZeros();
            }
        };
    }
}

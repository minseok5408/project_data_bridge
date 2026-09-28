package io.databridge.collector;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 한 원본 파일의 모든 행을 한 트랜잭션으로 저장하기 위한 요청입니다. */
public record FileBatch(String eventId, List<Event> events) {
    public static final int MAX_EVENTS = 10_000;
    public static final int MAX_BYTES = 16 * 1024 * 1024;

    public FileBatch {
        UUID.fromString(Objects.requireNonNull(eventId, "eventId required"));
        if (events == null || events.isEmpty() || events.size() > MAX_EVENTS)
            throw new IllegalArgumentException("File batch requires 1..10000 events");
        events = List.copyOf(events);
        Event first = events.getFirst();
        var ids = new HashSet<String>();
        long previousRow = 0;
        for (var event : events) {
            if (!first.comCd().equals(event.comCd())
                    || !first.sourceId().equals(event.sourceId())
                    || !first.equipmentId().equals(event.equipmentId())
                    || !first.sourceType().equals(event.sourceType()))
                throw new IllegalArgumentException(
                        "All file batch rows must use the same company, source, equipment and"
                                + " type");
            if (event.origin() == null
                    || event.origin().fileName() == null
                    || event.origin().fileName().isBlank()
                    || event.origin().row() == null
                    || event.origin().row() <= previousRow)
                throw new IllegalArgumentException(
                        "File batch origins require increasing positive row numbers");
            if (!first.origin().fileName().equals(event.origin().fileName()))
                throw new IllegalArgumentException(
                        "All file batch rows must use the same filename");
            if (!ids.add(event.eventId()))
                throw new IllegalArgumentException("Duplicate file batch row eventId");
            previousRow = event.origin().row();
        }
    }

    /** 파싱 중에도 행수와 실제 JSON 바이트 크기를 제한해 과도한 누적을 막습니다. */
    static final class Builder {
        private final List<Event> events = new ArrayList<>();
        private long bytes;

        Builder(String generation) {
            bytes =
                    ("{\"eventId\":\"" + generation + "\",\"events\":[]}")
                            .getBytes(StandardCharsets.UTF_8)
                            .length;
        }

        void add(Event event) {
            if (events.size() >= MAX_EVENTS)
                throw new IllegalArgumentException(
                        "FILE_BATCH_LIMIT: one file supports at most 10000 events");
            bytes +=
                    Json.write(event).getBytes(StandardCharsets.UTF_8).length
                            + (events.isEmpty() ? 0 : 1);
            if (bytes > MAX_BYTES)
                throw new IllegalArgumentException(
                        "FILE_BATCH_LIMIT: one file request exceeds 16 MiB UTF-8 JSON");
            events.add(event);
        }

        List<Event> events() {
            return List.copyOf(events);
        }
    }
}

package io.databridge.ims;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 한 파일의 모든 행을 함께 검증하고 하나의 트랜잭션으로 저장하는 전송 단위입니다. */
public record FileBatch(String eventId, List<Event> events) {
    public static final int MAX_EVENTS = 10_000;
    public static final int MAX_BYTES = 16 * 1024 * 1024;

    public FileBatch {
        Event.eventId(eventId);
        ValidationException.require(
                events != null && !events.isEmpty() && events.size() <= MAX_EVENTS,
                "events",
                "File batch must contain 1..10000 events");
        var identifiers = new HashSet<String>();
        Event first = null;
        long previousRow = 0;
        for (int index = 0; index < events.size(); index++) {
            var event = events.get(index);
            String field = "events[" + index + "]";
            ValidationException.require(event != null, field, "Null event");
            ValidationException.require(
                    identifiers.add(event.eventId()),
                    field + ".eventId",
                    "File batch event IDs must be unique");
            ValidationException.require(
                    !eventId.equals(event.eventId()),
                    field + ".eventId",
                    "Row event ID must differ from the file event ID");
            ValidationException.require(
                    event.origin() != null
                            && event.fileName() != null
                            && event.origin().row() != null,
                    field + ".origin",
                    "File batch events require an original filename and row number");
            ValidationException.require(
                    event.origin().row() > previousRow,
                    field + ".origin.row",
                    "File batch row numbers must be positive and strictly increasing");
            if (first != null) {
                ValidationException.require(
                        first.comCd().equals(event.comCd()),
                        field + ".com_cd",
                        "All file batch events must belong to the same company");
                ValidationException.require(
                        first.sourceId().equals(event.sourceId()),
                        field + ".sourceId",
                        "All file batch events must use the same source");
                ValidationException.require(
                        first.equipmentId().equals(event.equipmentId()),
                        field + ".equipmentId",
                        "All file batch events must use the same equipment");
                ValidationException.require(
                        first.sourceType().equals(event.sourceType()),
                        field + ".sourceType",
                        "All file batch events must use the same collection type");
                ValidationException.require(
                        Objects.equals(first.fileName(), event.fileName()),
                        field + ".origin.fileName",
                        "All file batch events must use the same original filename");
            }
            first = event;
            previousRow = event.origin().row();
        }
        events = List.copyOf(events);
    }
}

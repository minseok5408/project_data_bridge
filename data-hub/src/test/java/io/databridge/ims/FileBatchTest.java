package io.databridge.ims;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class FileBatchTest {
    static Event event(long row, int value) {
        return new Event(
                "1.0",
                UUID.randomUUID().toString(),
                "company",
                "input",
                "text",
                "EQ01",
                "2026-09-22T00:00:00Z",
                "2026-09-22T00:00:00Z",
                Map.of("value", new Event.Measurement(value, "sensor", null)),
                Map.of(),
                new Event.Origin("values.txt", row));
    }

    static Event changedValue(Event event, int value) {
        return new Event(
                event.schemaVersion(),
                event.eventId(),
                event.comCd(),
                event.sourceId(),
                event.sourceType(),
                event.equipmentId(),
                event.observedAt(),
                event.collectedAt(),
                Map.of("value", new Event.Measurement(value, "sensor", null)),
                event.context(),
                event.origin());
    }

    static FileBatch batch(Event... events) {
        return new FileBatch(UUID.randomUUID().toString(), List.of(events));
    }

    @Test
    void acceptsOrderedRowsWithGapsAndCopiesList() {
        var events = new ArrayList<>(List.of(event(1, 10), event(3, 20)));
        var batch = new FileBatch(UUID.randomUUID().toString(), events);
        events.clear();
        assertEquals(2, batch.events().size());
        assertThrows(UnsupportedOperationException.class, () -> batch.events().clear());
    }

    @Test
    void rejectsEmptyOversizedNullAndRepeatedRows() {
        String id = UUID.randomUUID().toString();
        assertThrows(ValidationException.class, () -> new FileBatch(id, null));
        assertThrows(ValidationException.class, () -> new FileBatch(id, List.of()));
        assertThrows(
                ValidationException.class,
                () ->
                        new FileBatch(
                                id, Collections.nCopies(FileBatch.MAX_EVENTS + 1, event(1, 1))));
        assertThrows(
                ValidationException.class,
                () -> new FileBatch(id, Arrays.asList(event(1, 1), null)));
        assertThrows(ValidationException.class, () -> batch(event(2, 1), event(1, 2)));
        assertThrows(ValidationException.class, () -> batch(event(1, 1), event(1, 2)));
        var first = event(1, 1);
        assertThrows(
                ValidationException.class, () -> new FileBatch(first.eventId(), List.of(first)));
        var repeated =
                new Event(
                        first.schemaVersion(),
                        first.eventId(),
                        first.comCd(),
                        first.sourceId(),
                        first.sourceType(),
                        first.equipmentId(),
                        first.observedAt(),
                        first.collectedAt(),
                        first.measurements(),
                        first.context(),
                        new Event.Origin(first.fileName(), 2L));
        assertEquals(
                "events[1].eventId",
                assertThrows(ValidationException.class, () -> batch(first, repeated)).field());
    }

    @Test
    void rejectsMissingOriginAndMixedIdentityBeforeStorage() throws Exception {
        var first = event(1, 1);
        var missing =
                new Event(
                        first.schemaVersion(),
                        first.eventId(),
                        first.comCd(),
                        first.sourceId(),
                        first.sourceType(),
                        first.equipmentId(),
                        first.observedAt(),
                        first.collectedAt(),
                        first.measurements(),
                        first.context(),
                        null);
        assertEquals(
                "events[0].origin",
                assertThrows(ValidationException.class, () -> batch(missing)).field());
        for (String field : List.of("com_cd", "sourceId", "equipmentId", "sourceType", "origin")) {
            var json =
                    (com.fasterxml.jackson.databind.node.ObjectNode)
                            Json.MAPPER.valueToTree(event(2, 2));
            if (field.equals("origin"))
                ((com.fasterxml.jackson.databind.node.ObjectNode) json.get(field))
                        .put("fileName", "other.txt");
            else json.put(field, field.equals("sourceType") ? "excel" : "other");
            var changed = Json.MAPPER.treeToValue(json, Event.class);
            var error = assertThrows(ValidationException.class, () -> batch(first, changed));
            assertEquals(
                    "events[1]." + (field.equals("origin") ? "origin.fileName" : field),
                    error.field());
        }
    }

    @Test
    void matchesCollectorBatchWireContractAndLimits() throws Exception {
        var original = batch(event(1, 10), event(4, 20));
        var collector =
                io.databridge.collector.Json.read(
                        Json.write(original), io.databridge.collector.FileBatch.class);
        assertEquals(
                original,
                Json.read(io.databridge.collector.Json.write(collector), FileBatch.class));
        assertEquals(io.databridge.collector.FileBatch.MAX_EVENTS, FileBatch.MAX_EVENTS);
        assertEquals(io.databridge.collector.FileBatch.MAX_BYTES, FileBatch.MAX_BYTES);
    }
}

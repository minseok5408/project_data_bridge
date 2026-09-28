package io.databridge.collector;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.*;
import java.util.*;

class CollectorTest {
    @TempDir Path temp;
    CollectorConfig config;
    CollectorConfig.Source source;
    Progress progress;
    TestReceiver receiver;
    Sender sender;

    @BeforeEach
    void setup() throws Exception {
        config = new CollectorConfig();
        source = new CollectorConfig.Source();
        source.id = "input";
        source.comCd = "company";
        source.equipmentId = "EQ01";
        source.type = "text";
        source.directory = ".";
        source.glob = "*.txt";
        source.settleSeconds = 0;
        source.skipLines = 0;
        var field = new CollectorConfig.Field();
        field.column = 1;
        source.fields.put("temperature", field);
        config.sources.add(source);
        progress = new Progress(temp.resolve("progress.json"));
        receiver = new TestReceiver();
        config.endpoint = receiver.endpoint();
        sender = new Sender(config, "collector-test-token-1234");
    }

    @AfterEach
    void close() {
        if (sender != null) sender.close();
        if (receiver != null) receiver.close();
    }

    SourceCollector collector() {
        return new SourceCollector(config, source, temp, progress, sender);
    }

    Path pendingFile(Path original) throws Exception {
        if (Files.exists(original)) return original;
        Path processing = temp.resolve(".databridge");
        if (!Files.exists(processing)) return original;
        try (var files = Files.walk(processing)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().equals(original.getFileName()))
                    .findFirst()
                    .orElse(original);
        }
    }

    void restoreErrorFile(String name) throws Exception {
        Files.move(temp.resolve("error").resolve(name), temp.resolve(name));
        Files.move(
                temp.resolve("error").resolve(name + FileLifecycle.RETRY_SUFFIX),
                temp.resolve(name + FileLifecycle.RETRY_SUFFIX));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12\n13\n", "12\n13"})
    void textArchivesAfterReadingAllRowsIncludingFinalLine(String content) throws Exception {
        Path file = temp.resolve("values.txt");
        Files.writeString(file, content);
        assertEquals(2, collector().poll());
        assertFalse(Files.exists(file));
        assertEquals(content, Files.readString(temp.resolve("complete/values.txt")));
        var restarted = new Progress(temp.resolve("progress.json"));
        assertEquals(0, new SourceCollector(config, source, temp, restarted, sender).poll());
        assertEquals(0, restarted.status().get("legacyRowCheckpoints"));
        assertEquals(2, receiver.events.size());
    }

    @Test
    void batchLinesDoesNotSplitAFileTransaction() throws Exception {
        source.batchLines = 2;
        Files.writeString(temp.resolve("values.txt"), "10\n20\n30\n");
        assertEquals(3, collector().poll());
        assertTrue(Files.exists(temp.resolve("complete/values.txt")));
        assertEquals(3, receiver.events.size());
        assertEquals(1, receiver.requests.get());
        assertEquals(0, collector().poll());
        assertFalse(Files.exists(temp.resolve("progress.json")));
    }

    @Test
    void invalidLinePreventsTheWholeFileFromBeingSent() throws Exception {
        var file = temp.resolve("values.txt");
        Files.writeString(file, "1\nbad\n");
        assertThrows(Exception.class, () -> collector().poll());
        assertEquals(0, receiver.requests.get());
        assertEquals(0, receiver.events.size());
        assertEquals("1\nbad\n", Files.readString(temp.resolve("error/values.txt")));
        assertFalse(Files.exists(temp.resolve("error/values.txt.retry.json")));
        Files.move(temp.resolve("error/values.txt"), file);
        Files.writeString(file, "1\n2\n");
        assertEquals(2, collector().poll());
        assertEquals(2, receiver.events.size());
    }

    @Test
    void directSendFailureRetriesTheWholeFileWithTheSameIdentity() throws Exception {
        Files.writeString(temp.resolve("values.txt"), "1\n2\n");
        receiver.failAt = 1;
        assertThrows(Exception.class, () -> collector().poll());
        assertEquals(0, receiver.events.size());
        assertTrue(Files.exists(temp.resolve("error/values.txt.retry.json")));
        assertEquals(0, collector().poll());
        restoreErrorFile("values.txt");
        progress = new Progress(temp.resolve("progress.json"));
        receiver.failAt = 0;
        assertEquals(2, collector().poll());
        assertEquals(2, receiver.events.size());
        assertEquals(receiver.batchBodies.get(0), receiver.batchBodies.get(1));
        assertEquals(0, collector().poll());
        assertFalse(Files.exists(temp.resolve("progress.json")));
    }

    @Test
    void lostAcknowledgementReplaysTheSameBatchWithoutDuplicatingRows() throws Exception {
        Files.writeString(temp.resolve("values.txt"), "1\n2\n");
        receiver.acknowledgement = "{}";
        assertThrows(Exception.class, () -> collector().poll());
        assertEquals(2, receiver.events.size());
        restoreErrorFile("values.txt");
        progress = new Progress(temp.resolve("progress.json"));
        receiver.acknowledgement = null;
        assertEquals(2, collector().poll());
        assertEquals(2, receiver.events.size());
        assertEquals(2, receiver.requests.get());
        assertEquals(receiver.batchBodies.get(0), receiver.batchBodies.get(1));
        assertEquals(1, receiver.receipts.size());
    }

    @Test
    void cp949RegexAndNullHandling() throws Exception {
        source.encoding = "MS949";
        source.pattern = "온도=(?<temp>.*)";
        source.fields.get("temperature").group = "temp";
        source.fields.get("temperature").column = null;
        Files.writeString(
                temp.resolve("values.txt"), "온도=25.2\n", java.nio.charset.Charset.forName("MS949"));
        config.validate();
        assertEquals(1, collector().poll());
        var field = source.fields.get("temperature");
        field.required = false;
        assertNull(EventFactory.convert("", field, java.time.ZoneId.of("Asia/Seoul")));
        assertThrows(
                NumberFormatException.class,
                () -> EventFactory.convert("NaN", field, java.time.ZoneId.of("UTC")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"xlsx", "xls"})
    void excelArchivesWholeFileAndReusedNameIsNewInput(String extension) throws Exception {
        source.type = "excel";
        source.glob = "*." + extension;
        source.firstDataRow = 1;
        var file = temp.resolve("data." + extension);
        workbook(file, extension, 10);
        assertEquals(1, collector().poll());
        assertEquals(0, collector().poll());
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(temp.resolve("complete/data." + extension)));
        byte[] first = Files.readAllBytes(temp.resolve("complete/data." + extension));
        workbook(file, extension, 20, 30);
        assertEquals(2, collector().poll());
        assertArrayEquals(first, Files.readAllBytes(temp.resolve("complete/data." + extension)));
        try (var files = Files.list(temp.resolve("complete"))) {
            assertEquals(2, files.count());
        }
        assertEquals(3, receiver.events.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"xlsx", "xls"})
    void excelSendFailureRetriesTheWholeFile(String extension) throws Exception {
        source.type = "excel";
        source.glob = "*." + extension;
        source.firstDataRow = 1;
        workbook(temp.resolve("data." + extension), extension, 10, 20);
        receiver.failAt = 1;
        assertThrows(Exception.class, () -> collector().poll());
        assertEquals(0, receiver.events.size());
        restoreErrorFile("data." + extension);
        progress = new Progress(temp.resolve("progress.json"));
        receiver.failAt = 0;
        assertEquals(2, collector().poll());
        assertEquals(0, collector().poll());
        assertEquals(2, receiver.events.size());
        assertEquals(receiver.batchBodies.get(0), receiver.batchBodies.get(1));
    }

    @Test
    void fixedCellsAndUncachedFormula() throws Exception {
        source.type = "excel";
        source.glob = "*.xlsx";
        source.layout = "cells";
        source.fields.get("temperature").column = null;
        source.fields.get("temperature").cell = "B3";
        try (var workbook = new XSSFWorkbook();
                var output = Files.newOutputStream(temp.resolve("data.xlsx"))) {
            workbook.createSheet().createRow(2).createCell(1).setCellValue(15);
            workbook.write(output);
        }
        assertEquals(1, collector().poll());
        assertEquals(0, collector().poll());
        try (var workbook = new XSSFWorkbook();
                var output = Files.newOutputStream(temp.resolve("formula.xlsx"))) {
            workbook.createSheet().createRow(2).createCell(1).setCellFormula("1+1");
            workbook.write(output);
        }
        assertThrows(Exception.class, () -> collector().poll());
    }

    @Test
    void changedMappingCannotResendAnAttemptedBatchWithDifferentValues() throws Exception {
        Files.writeString(temp.resolve("values.txt"), "1\n2\n");
        receiver.acknowledgement = "{}";
        assertThrows(Exception.class, () -> collector().poll());
        restoreErrorFile("values.txt");
        source.fields.get("temperature").scale = java.math.BigDecimal.TEN;
        var error = assertThrows(Exception.class, () -> collector().poll());
        assertTrue(error.getMessage().contains("FILE_BATCH_CHANGED"));
        assertEquals(1, receiver.requests.get());
        assertTrue(Files.exists(temp.resolve("error/values.txt.retry.json")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"csv", "serial", "modbus_rtu"})
    void unsupportedProtocolsRejected(String type) {
        source.type = type;
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void eventContractRejectsBadValuesAndUnknownFields() throws Exception {
        assertThrows(
                IllegalArgumentException.class, () -> new Event.Measurement(-1, "counter", "ea"));
        assertThrows(
                IllegalArgumentException.class, () -> new Event.Measurement(1, "status", null));
        assertThrows(Exception.class, () -> Json.read("{\"unknown\":1}", Event.Ack.class));
    }

    @Test
    void completedFileNameCollisionPreservesBothFilesAndCleansProgress() throws Exception {
        var file = temp.resolve("values.txt");
        Files.writeString(file, "1\n");
        assertEquals(1, collector().poll());
        Files.writeString(file, "2\n");
        assertEquals(1, collector().poll());
        assertEquals("1\n", Files.readString(temp.resolve("complete/values.txt")));
        try (var files = Files.list(temp.resolve("complete"))) {
            var contents =
                    files.map(
                                    path -> {
                                        try {
                                            return Files.readString(path);
                                        } catch (Exception e) {
                                            throw new RuntimeException(e);
                                        }
                                    })
                            .toList();
            assertEquals(Set.of("1\n", "2\n"), new HashSet<>(contents));
        }
        assertEquals(0, progress.status().get("legacyRowCheckpoints"));
    }

    @Test
    void errorNameCollisionPreservesBothFilesAndDoesNotRetryAutomatically() throws Exception {
        var file = temp.resolve("values.txt");
        Files.writeString(file, "bad1\n");
        assertThrows(Exception.class, () -> collector().poll());
        Files.writeString(file, "bad2\n");
        assertThrows(Exception.class, () -> collector().poll());
        assertEquals("bad1\n", Files.readString(temp.resolve("error/values.txt")));
        try (var files = Files.list(temp.resolve("error"))) {
            assertEquals(
                    2,
                    files.filter(path -> !path.toString().endsWith(FileLifecycle.RETRY_SUFFIX))
                            .count());
        }
        assertEquals(0, collector().poll());
        assertEquals(0, receiver.requests.get());
    }

    @Test
    void failedArchivePreservesIdentityAndRetryCannotDuplicateAcknowledgedRows() throws Exception {
        var file = temp.resolve("values.txt");
        Files.writeString(file, "1\n2\n");
        Files.writeString(temp.resolve("complete"), "blocks directory creation");
        assertThrows(Exception.class, () -> collector().poll());
        assertTrue(Files.exists(pendingFile(file)));
        assertEquals(2, receiver.events.size());
        assertFalse(Files.exists(temp.resolve("error")));
        Files.delete(temp.resolve("complete"));
        assertEquals(2, collector().poll());
        assertTrue(Files.exists(temp.resolve("complete/values.txt")));
        assertEquals(2, receiver.events.size());
        assertEquals(receiver.batchBodies.get(0), receiver.batchBodies.get(1));
    }

    @Test
    void errorMoveFailurePreservesInputAndReportsBothFailures() throws Exception {
        Files.writeString(temp.resolve("values.txt"), "bad\n");
        Files.writeString(temp.resolve("error"), "blocks directory creation");
        var error = assertThrows(Exception.class, () -> collector().poll());
        assertTrue(error.getMessage().contains("error"));
        assertTrue(Files.exists(pendingFile(temp.resolve("values.txt"))));
        assertEquals(0, receiver.requests.get());
    }

    @Test
    void interruptedCollectionLeavesInputForRestart() throws Exception {
        Files.writeString(temp.resolve("values.txt"), "1\n");
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> collector().poll());
        } finally {
            Thread.interrupted();
        }
        assertTrue(Files.exists(temp.resolve("values.txt")));
        assertFalse(Files.exists(temp.resolve("error")));
    }

    @Test
    void recentlyWrittenFileWaitsBeforeReadingOrMoving() throws Exception {
        source.settleSeconds = 60;
        Files.writeString(temp.resolve("values.txt"), "1\n");
        assertEquals(0, collector().poll());
        assertEquals(0, receiver.requests.get());
        assertTrue(Files.exists(temp.resolve("values.txt")));
        assertFalse(Files.exists(temp.resolve("complete")));
    }

    @Test
    void emptyFileCanCompleteWithoutSendingAndArchiveFoldersAreNotScanned() throws Exception {
        Files.writeString(temp.resolve("empty.txt"), "");
        Files.createDirectories(temp.resolve("error"));
        Files.writeString(temp.resolve("error/ignored.txt"), "10\n");
        assertEquals(0, collector().poll());
        assertTrue(Files.exists(temp.resolve("complete/empty.txt")));
        assertEquals(0, receiver.requests.get());
    }

    @Test
    void aBadFileDoesNotPreventOtherFilesFromCompleting() throws Exception {
        Files.writeString(temp.resolve("a.txt"), "bad\n");
        Files.writeString(temp.resolve("b.txt"), "20\n");
        assertThrows(Exception.class, () -> collector().poll());
        assertTrue(Files.exists(temp.resolve("error/a.txt")));
        assertTrue(Files.exists(temp.resolve("complete/b.txt")));
        assertEquals(1, receiver.events.size());
    }

    @Test
    void corruptExcelIsMovedToErrorWithoutSending() throws Exception {
        source.type = "excel";
        source.glob = "*.xlsx";
        Files.writeString(temp.resolve("bad.xlsx"), "not an Excel file");
        assertThrows(Exception.class, () -> collector().poll());
        assertTrue(Files.exists(temp.resolve("error/bad.xlsx")));
        assertEquals(0, receiver.requests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"text", "xlsx", "xls"})
    void fileChangedDuringTransmissionPreservesTheAttemptedIdentityForReview(String type)
            throws Exception {
        boolean text = type.equals("text");
        Path file = temp.resolve(text ? "values.txt" : "data." + type);
        if (text) Files.writeString(file, "10\n");
        else {
            source.type = "excel";
            source.glob = "*." + type;
            source.firstDataRow = 1;
            workbook(file, type, 10);
        }
        receiver.onRequest =
                () -> {
                    receiver.onRequest = null;
                    try {
                        if (text)
                            Files.writeString(pendingFile(file), "20\n", StandardOpenOption.APPEND);
                        else workbook(pendingFile(file), type, 10, 20);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                };
        assertThrows(Exception.class, () -> collector().poll());
        assertFalse(Files.exists(temp.resolve("complete").resolve(file.getFileName())));
        assertTrue(
                Files.exists(
                        temp.resolve("error")
                                .resolve(file.getFileName() + FileLifecycle.RETRY_SUFFIX)));
        restoreErrorFile(file.getFileName().toString());
        assertThrows(Exception.class, () -> collector().poll());
        assertEquals(1, receiver.events.size());
        assertEquals(1, receiver.requests.get());
    }

    @Test
    void textSelectsPayloadAndSeparatesMetadataInOneBatch() throws Exception {
        source.skipLines = 1;
        source.batchLines = 1;
        source.fields.clear();
        var configJson =
                """
                {"item_code":{"column":2,"type":"str","target":"item_code","headerName":"항목코드"},
                 "cmnt":{"column":3,"type":"str","target":"cmnt","headerName":"비고"}}
                """;
        var metadata = Json.MAPPER.readTree(configJson);
        metadata.properties()
                .forEach(
                        e ->
                                source.fields.put(
                                        e.getKey(),
                                        Json.MAPPER.convertValue(
                                                e.getValue(), CollectorConfig.Field.class)));
        var value = new CollectorConfig.Field();
        value.column = 1;
        value.headerName = "값";
        source.payload.put("value", value);
        config.validate();
        Files.writeString(
                temp.resolve("values.txt"),
                "\uFEFF값\t항목코드\t비고\t미선택\n12\tTEXT_001\t첫 번째 테스트\t버림\n20\tTEXT_002\t두 번째 테스트\t버림");
        assertEquals(2, collector().poll());
        assertEquals(1, receiver.requests.get());
        var event = receiver.events.get(1);
        assertEquals(Map.of("값", "20"), event.filePayload());
        assertEquals("TEXT_002", event.fileItemCode());
        assertEquals("두 번째 테스트", event.fileCmnt());
        assertTrue(Files.exists(temp.resolve("complete/values.txt")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"xlsx", "xls"})
    void excelCollectsMultipleNamedPayloadItemsAndMetadata(String extension) throws Exception {
        source.type = "excel";
        source.glob = "*." + extension;
        source.firstDataRow = 2;
        source.fields.clear();
        var time = new CollectorConfig.Field();
        time.headerCell = "C1";
        time.headerName = "측정시각";
        time.type = "datetime";
        time.target = "observed_at";
        time.datetimeFormat = "yyyy-MM-dd HH:mm:ss";
        var item = new CollectorConfig.Field();
        item.headerCell = "A1";
        item.headerName = "항목코드";
        item.type = "str";
        item.target = "item_code";
        var note = new CollectorConfig.Field();
        note.headerCell = "D1";
        note.headerName = "비고";
        note.type = "str";
        note.target = "cmnt";
        note.required = false;
        source.fields.put("observed_at", time);
        source.fields.put("item_code", item);
        source.fields.put("cmnt", note);
        var value = new CollectorConfig.Field();
        value.headerCell = "B1";
        value.headerName = "값";
        value.type = "float";
        var second = new CollectorConfig.Field();
        second.headerCell = "E1";
        second.headerName = "값2";
        second.type = "float";
        second.scale = new java.math.BigDecimal("0.1");
        source.payload.put("value", value);
        source.payload.put("value2", second);
        config.validate();
        try (var book = extension.equals("xls") ? new HSSFWorkbook() : new XSSFWorkbook();
                var out = Files.newOutputStream(temp.resolve("data." + extension))) {
            var sheet = book.createSheet();
            var header = sheet.createRow(0);
            var row = sheet.createRow(1);
            var titles = List.of("항목코드", "값", "측정시각", "비고", "값2", "미선택");
            var values = List.of("P001", "20", "2026-09-28 12:34:56", "첫 번째 테스트", "305", "버림");
            for (int i = 0; i < 6; i++) {
                header.createCell(i).setCellValue(titles.get(i));
                row.createCell(i).setCellValue(values.get(i));
            }
            book.write(out);
        }
        assertEquals(1, collector().poll());
        var event = receiver.events.getFirst();
        assertEquals("2026-09-28T12:34:56+09:00", event.observedAt());
        assertEquals(Map.of("값", "20", "값2", "30.5"), event.filePayload());
        assertEquals("P001", event.fileItemCode());
        assertEquals("첫 번째 테스트", event.fileCmnt());
        assertEquals(extension, event.fileExtension());
    }

    @Test
    void filePayloadValidatesBoundsAndSupportsNullAndFallbackNames() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Event.FileData(Map.of("a", Map.of("b", 1)), null, null));
        assertThrows(
                IllegalArgumentException.class, () -> new Event.FileData(Map.of(), null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Event.FileData(Map.of("a", "1"), "x".repeat(256), null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Event.FileData(Map.of("a", "1"), null, "한".repeat(22000)));
        source.fields.clear();
        var optional = new CollectorConfig.Field();
        optional.column = 1;
        optional.required = false;
        source.payload.put("value", optional);
        config.validate();
        var event = EventFactory.create(config, source, Map.of(), new Event.Origin("DATA.TXT", 1L));
        assertTrue(event.filePayload().containsKey("value"));
        assertNull(event.filePayload().get("value"));
        assertEquals(event.fileData(), Json.read(Json.write(event), Event.class).fileData());
        assertEquals("txt", event.fileExtension());
    }

    @Test
    void nestedPayloadRejectsAmbiguousKeysAndDuplicateColumnTargets() throws Exception {
        source.fields.clear();
        var first = new CollectorConfig.Field();
        first.column = 1;
        first.headerName = "값";
        var second = new CollectorConfig.Field();
        second.column = 2;
        second.headerName = "값";
        source.skipLines = 1;
        source.payload.put("value", first);
        source.payload.put("value2", second);
        assertThrows(IllegalArgumentException.class, config::validate);
        second.headerName = "값2";
        config.validate();
        source.fields.put("value", first);
        assertThrows(IllegalArgumentException.class, config::validate);
        source.fields.clear();
        var item = new CollectorConfig.Field();
        item.column = 3;
        item.type = "str";
        item.target = "item_code";
        source.fields.put("code1", item);
        source.fields.put("code2", item);
        assertThrows(IllegalArgumentException.class, config::validate);
        source.fields.clear();
        item.target = "cmnt";
        source.fields.put("note1", item);
        source.fields.put("note2", item);
        assertThrows(IllegalArgumentException.class, config::validate);
        source.fields.clear();
        first.target = "observed_at";
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void nestedPayloadLoadsFromJsonAndAllowsHeaderCellsAboveData() throws Exception {
        String input =
                """
                {"sources":[{"id":"excel","com_cd":"company","equipmentId":"pc","type":"excel","directory":".","glob":"*.xlsx","firstDataRow":3,
                  "payload":{"value":{"type":"float","headerCell":"C1","headerName":"값"},
                             "value2":{"type":"float","headerCell":"D2","headerName":"값2"}}}]}
                """;
        var loaded = Json.read(input, CollectorConfig.class);
        loaded.validate();
        var fields = loaded.sources.getFirst().payload;
        assertEquals(3, fields.get("value").column);
        assertEquals(4, fields.get("value2").column);
        var event =
                EventFactory.create(
                        loaded,
                        loaded.sources.getFirst(),
                        Map.of("value", "20", "value2", "30"),
                        null);
        assertEquals(Map.of("값", "20", "값2", "30"), event.filePayload());
    }

    @ParameterizedTest
    @ValueSource(strings = {"text", "excel"})
    void mismatchedConfiguredHeaderMovesFileToError(String type) throws Exception {
        source.type = type;
        source.fields.get("temperature").headerName = "온도";
        if (type.equals("text")) {
            source.skipLines = 1;
            Files.writeString(temp.resolve("bad.txt"), "수량\n12\n");
        } else {
            source.glob = "*.xlsx";
            source.firstDataRow = 2;
            source.fields.get("temperature").headerCell = "A1";
            try (var book = new XSSFWorkbook();
                    var out = Files.newOutputStream(temp.resolve("bad.xlsx"))) {
                var sheet = book.createSheet();
                sheet.createRow(0).createCell(0).setCellValue("수량");
                sheet.createRow(1).createCell(0).setCellValue(12);
                book.write(out);
            }
        }
        config.validate();
        assertThrows(Exception.class, () -> collector().poll());
        assertEquals(0, receiver.requests.get());
        assertTrue(
                Files.exists(
                        temp.resolve(type.equals("text") ? "error/bad.txt" : "error/bad.xlsx")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"text", "xlsx", "xls"})
    void invalidEleventhRowSendsNoneOfTheTenValidRows(String type) throws Exception {
        String name = type.equals("text") ? "values.txt" : "data." + type;
        Path file = temp.resolve(name);
        if (type.equals("text")) Files.writeString(file, "1\n".repeat(10) + "bad\n");
        else {
            source.type = "excel";
            source.glob = "*." + type;
            source.firstDataRow = 1;
            try (var book = type.equals("xls") ? new HSSFWorkbook() : new XSSFWorkbook();
                    var output = Files.newOutputStream(file)) {
                var sheet = book.createSheet();
                for (int index = 0; index < 10; index++)
                    sheet.createRow(index).createCell(0).setCellValue(1);
                sheet.createRow(10).createCell(0).setCellValue("bad");
                book.write(output);
            }
        }
        var error = assertThrows(Exception.class, () -> collector().poll());
        assertTrue(error.getMessage().contains("11"));
        assertEquals(0, receiver.requests.get());
        assertTrue(receiver.events.isEmpty());
        assertTrue(Files.exists(temp.resolve("error").resolve(name)));
        assertFalse(Files.exists(temp.resolve("error").resolve(name + FileLifecycle.RETRY_SUFFIX)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1\n", "1\n2\n3\n", "1\nbad\n", ""})
    void editedAttemptedFilePreservesItsIdentityEvenWhenParsingFails(String changed)
            throws Exception {
        Path file = Files.writeString(temp.resolve("values.txt"), "1\n2\n");
        receiver.acknowledgement = "{}";
        assertThrows(Exception.class, () -> collector().poll());
        String retry = Files.readString(temp.resolve("error/values.txt.retry.json"));
        restoreErrorFile("values.txt");
        Files.writeString(file, changed);
        assertThrows(Exception.class, () -> collector().poll());
        assertEquals(1, receiver.requests.get());
        assertEquals(retry, Files.readString(temp.resolve("error/values.txt.retry.json")));
        restoreErrorFile("values.txt");
        Files.writeString(file, "1\n2\n");
        receiver.acknowledgement = null;
        assertEquals(2, collector().poll());
        assertEquals(2, receiver.requests.get());
        assertEquals(2, receiver.events.size());
        assertEquals(receiver.batchBodies.get(0), receiver.batchBodies.get(1));
    }

    @Test
    void changedFileDuringWholeFileValidationDoesNotSendASnapshot() throws Exception {
        Path file = Files.writeString(temp.resolve("values.txt"), "1\n");
        source.fields =
                new LinkedHashMap<>(source.fields) {
                    private boolean changed;

                    @Override
                    public CollectorConfig.Field get(Object key) {
                        if (!changed) {
                            changed = true;
                            try {
                                Files.writeString(
                                        pendingFile(file), "2\n", StandardOpenOption.APPEND);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                        return super.get(key);
                    }
                };
        assertEquals(0, collector().poll());
        assertEquals(0, receiver.requests.get());
        assertTrue(Files.exists(pendingFile(file)));
        assertEquals(2, collector().poll());
        assertEquals(1, receiver.requests.get());
    }

    @Test
    void tenThousandRowsUseOneRequestAndTenThousandOneSendNothing() throws Exception {
        Files.writeString(temp.resolve("limit.txt"), "1\n".repeat(10_000));
        assertEquals(10_000, collector().poll());
        assertEquals(1, receiver.requests.get());
        assertEquals(10_000, receiver.batches.getFirst().events().size());
        Files.writeString(temp.resolve("over.txt"), "1\n".repeat(10_001));
        var error = assertThrows(Exception.class, () -> collector().poll());
        assertTrue(error.getMessage().contains("FILE_BATCH_LIMIT"));
        assertEquals(1, receiver.requests.get());
        assertTrue(Files.exists(temp.resolve("error/over.txt")));
        assertFalse(Files.exists(temp.resolve("error/over.txt.retry.json")));
    }

    @Test
    void utf8SerializedWholeRequestLimitIsCheckedBeforeSending() throws Exception {
        source.fields.get("temperature").type = "str";
        source.fields.get("temperature").kind = "text";
        Files.writeString(temp.resolve("large.txt"), ("한".repeat(300_000) + "\n").repeat(10));
        var error = assertThrows(Exception.class, () -> collector().poll());
        assertTrue(error.getMessage().contains("FILE_BATCH_LIMIT"));
        assertEquals(0, receiver.requests.get());
        assertFalse(Files.exists(temp.resolve("error/large.txt.retry.json")));
    }

    private void workbook(Path file, String extension, double... values) throws Exception {
        try (var workbook = extension.equals("xls") ? new HSSFWorkbook() : new XSSFWorkbook();
                var output = Files.newOutputStream(file)) {
            var sheet = workbook.createSheet();
            for (int i = 0; i < values.length; i++)
                sheet.createRow(i).createCell(0).setCellValue(values[i]);
            workbook.write(output);
        }
    }
}

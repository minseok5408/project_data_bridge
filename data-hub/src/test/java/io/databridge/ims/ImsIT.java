package io.databridge.ims;

import static org.junit.jupiter.api.Assertions.*;

import io.databridge.collector.*;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;

class ImsIT {
    @Test
    void eleventhRowSqlFailureRollsBackFileAndNewEquipmentWithoutTouchingEarlierFiles()
            throws Exception {
        var keptRow = FileBatchTest.event(1, 99);
        keptRow =
                new Event(
                        keptRow.schemaVersion(),
                        keptRow.eventId(),
                        keptRow.comCd(),
                        keptRow.sourceId(),
                        keptRow.sourceType(),
                        "EXISTING",
                        keptRow.observedAt(),
                        keptRow.collectedAt(),
                        keptRow.measurements(),
                        keptRow.context(),
                        keptRow.origin());
        repository.ingest(FileBatchTest.batch(keptRow));
        var before = repository.listJsonData(null, null, null, 0, 100);
        var events = new ArrayList<Event>();
        for (int row = 1; row <= 12; row++) {
            var event = FileBatchTest.event(row, row);
            events.add(
                    new Event(
                            event.schemaVersion(),
                            event.eventId(),
                            event.comCd(),
                            event.sourceId(),
                            event.sourceType(),
                            event.equipmentId(),
                            event.observedAt(),
                            event.collectedAt(),
                            event.measurements(),
                            event.context(),
                            event.origin(),
                            new Event.FileData(
                                    Map.of("value", Integer.toString(row)),
                                    row == 11 ? "FAIL" : null,
                                    null)));
        }
        var batch = new FileBatch(UUID.randomUUID().toString(), events);
        try (var db = DriverManager.getConnection(url, user, password);
                var statement = db.createStatement()) {
            statement.execute(
                    """
                    CREATE TRIGGER fail_eleventh BEFORE INSERT ON data_json FOR EACH ROW
                    BEGIN
                        IF NEW.item_code='FAIL' THEN
                            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test eleventh row failure';
                        END IF;
                    END
                    """);
            assertThrows(java.sql.SQLException.class, () -> repository.ingest(batch));
            assertEquals(before, repository.listJsonData(null, null, null, 0, 100));
            assertEquals(1, tableCount("equipment"));
            assertEquals(1, tableCount("file_import"));
            statement.execute("DROP TRIGGER fail_eleventh");
        }
        assertEquals("accepted", repository.ingest(batch));
        assertEquals(13, tableCount("data_json"));
        assertEquals(2, tableCount("equipment"));
        assertEquals(2, tableCount("file_import"));
    }

    @Test
    void fileReceiptFailureAlsoRollsBackAllRowsAndEquipment() throws Exception {
        var batch = FileBatchTest.batch(FileBatchTest.event(1, 1), FileBatchTest.event(2, 2));
        try (var db = DriverManager.getConnection(url, user, password);
                var statement = db.createStatement()) {
            statement.execute(
                    "CREATE TRIGGER fail_receipt BEFORE INSERT ON file_import FOR EACH ROW SIGNAL"
                            + " SQLSTATE '45000' SET MESSAGE_TEXT='test receipt failure'");
            assertThrows(java.sql.SQLException.class, () -> repository.ingest(batch));
        }
        assertEquals(0, tableCount("data_json"));
        assertEquals(0, tableCount("equipment"));
        assertEquals(0, tableCount("file_import"));
    }

    @Test
    void concurrentFileRetriesAndRestartWriteOneReceiptAndRejectChangedRoster() throws Exception {
        var batch = FileBatchTest.batch(FileBatchTest.event(1, 10), FileBatchTest.event(2, 20));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < 8; i++)
                futures.add(executor.submit(() -> repository.ingest(batch)));
            int accepted = 0;
            for (var future : futures) {
                String result = future.get(15, TimeUnit.SECONDS);
                assertTrue(Set.of("accepted", "duplicate").contains(result));
                if (result.equals("accepted")) accepted++;
            }
            assertEquals(1, accepted);
        }
        assertEquals("duplicate", new Repository(url, user, password).ingest(batch));
        var changed =
                new FileBatch(
                        batch.eventId(),
                        List.of(
                                batch.events().getFirst(),
                                FileBatchTest.changedValue(batch.events().getLast(), 21)));
        assertThrows(Repository.Conflict.class, () -> repository.ingest(changed));
        assertThrows(
                Repository.Conflict.class,
                () ->
                        repository.ingest(
                                new FileBatch(
                                        batch.eventId(), List.of(batch.events().getFirst()))));
        var added = new ArrayList<>(batch.events());
        added.add(FileBatchTest.event(3, 30));
        assertThrows(
                Repository.Conflict.class,
                () -> repository.ingest(new FileBatch(batch.eventId(), added)));
        assertEquals(2, tableCount("data_json"));
        assertEquals(1, tableCount("file_import"));
        assertEquals(1, tableCount("equipment"));
    }

    @Test
    void batchCannotAppendToPartiallyStoredLegacyRowsButCanAcknowledgeAnIdenticalWholeFile()
            throws Exception {
        var first = FileBatchTest.event(1, 10);
        var second = FileBatchTest.event(2, 20);
        var batch = FileBatchTest.batch(first, second);
        repository.ingest(first);
        assertThrows(Repository.Conflict.class, () -> repository.ingest(batch));
        assertEquals(1, tableCount("data_json"));
        assertEquals(0, tableCount("file_import"));
        repository.ingest(second);
        assertEquals("duplicate", repository.ingest(batch));
        assertEquals(2, tableCount("data_json"));
        assertEquals(1, tableCount("file_import"));
        var conflict = FileBatchTest.batch(first, FileBatchTest.changedValue(second, 99));
        assertThrows(Repository.Conflict.class, () -> repository.ingest(conflict));
        assertEquals(1, tableCount("file_import"));
    }

    @Test
    void fileBatchCrossesBulkLookupBoundaryWithoutLosingRows() throws Exception {
        var events = new ArrayList<Event>();
        for (int row = 1; row <= 501; row++) events.add(FileBatchTest.event(row, row));
        assertEquals(
                "accepted", repository.ingest(new FileBatch(UUID.randomUUID().toString(), events)));
        assertEquals(
                "duplicate",
                repository.ingest(new FileBatch(UUID.randomUUID().toString(), events)));
        assertEquals(501, tableCount("data_json"));
        assertEquals(2, tableCount("file_import"));
    }

    @Test
    void laterInvalidRowAndOversizedBodyStoreNothingThroughHttp() throws Exception {
        var batch = FileBatchTest.batch(FileBatchTest.event(1, 10), FileBatchTest.event(2, 20));
        var json = Json.MAPPER.valueToTree(batch);
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.path("events").get(1).path("origin"))
                .put("row", 2.5);
        var config = new ImsConfig();
        config.port = 0;
        config.maxBodyBytes = 4096;
        try (var server = new ImsServer(config, repository, TOKEN);
                var client = HttpClient.newHttpClient()) {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            var response = post(client, base, Json.write(json), batch.eventId());
            assertEquals(400, response.statusCode());
            assertEquals(
                    "events[1].origin.row",
                    Json.MAPPER.readTree(response.body()).path("field").asText());
            assertEquals(413, post(client, base, " ".repeat(4097), batch.eventId()).statusCode());
            assertEquals(0, tableCount("data_json"));
            assertEquals(0, tableCount("equipment"));
            assertEquals(0, tableCount("file_import"));
            response = post(client, base, Json.write(batch), batch.eventId());
            assertEquals(200, response.statusCode());
            assertEquals(
                    new Event.Ack(batch.eventId(), "accepted"),
                    Json.read(response.body(), Event.Ack.class));
            assertEquals(
                    "duplicate",
                    Json.read(
                                    post(client, base, Json.write(batch), batch.eventId()).body(),
                                    Event.Ack.class)
                            .status());
        }
    }

    @Test
    void receiptSchemaUpgradePreservesExistingRowsAndRequiresInnoDb() throws Exception {
        repository.ingest(FileBatchTest.event(1, 1));
        try (var db = DriverManager.getConnection(url, user, password);
                var statement = db.createStatement()) {
            statement.execute("DROP TABLE file_import");
            var missing =
                    assertThrows(
                            java.sql.SQLException.class,
                            () -> new Repository(url, user, password, false));
            assertTrue(missing.getMessage().contains("file_import"));
            new Repository(url, user, password, true);
            assertEquals(1, tableCount("data_json"));
            assertEquals(0, tableCount("file_import"));
            statement.execute("ALTER TABLE file_import ENGINE=MyISAM");
            var wrongEngine =
                    assertThrows(
                            java.sql.SQLException.class,
                            () -> new Repository(url, user, password, false));
            assertTrue(wrongEngine.getMessage().contains("InnoDB"));
            assertEquals(1, tableCount("data_json"));
        }
    }

    @Test
    void fileReceiptAndExistingDeliveryIdsCannotBeReusedAcrossPacketTypes() throws Exception {
        var single = FileBatchTest.event(1, 1);
        repository.ingest(single);
        var row = FileBatchTest.event(2, 2);
        assertThrows(
                Repository.Conflict.class,
                () -> repository.ingest(new FileBatch(single.eventId(), List.of(row))));
        var modbus = production("company", 0, 1, 100, "HIGH_LOW");
        repository.ingest(modbus);
        assertThrows(
                Repository.Conflict.class,
                () -> repository.ingest(new FileBatch(modbus.eventId(), List.of(row))));
        var batch = FileBatchTest.batch(row);
        assertEquals("accepted", repository.ingest(batch));
        var collision =
                new Event(
                        row.schemaVersion(),
                        batch.eventId(),
                        row.comCd(),
                        row.sourceId(),
                        row.sourceType(),
                        row.equipmentId(),
                        row.observedAt(),
                        row.collectedAt(),
                        row.measurements(),
                        row.context(),
                        row.origin());
        assertThrows(Repository.Conflict.class, () -> repository.ingest(collision));
        assertThrows(
                Repository.Conflict.class, () -> repository.ingest(FileBatchTest.batch(collision)));
        assertThrows(
                Repository.Conflict.class,
                () ->
                        repository.ingest(
                                new ModbusData(
                                        batch.eventId(),
                                        modbus.comCd(),
                                        modbus.nodeId(),
                                        modbus.datas())));
        assertEquals(2, tableCount("data_json"));
        assertEquals(1, tableCount("file_import"));
    }

    private int tableCount(String table) throws Exception {
        assertTrue(Set.of("equipment", "data_json", "file_import").contains(table));
        try (var db = DriverManager.getConnection(url, user, password);
                var statement = db.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    @BeforeAll
    static void requireTestDatabase() {
        String base = System.getenv("IMS_TEST_DB_URL");
        if (base == null || !base.startsWith("jdbc:mariadb://") || !base.endsWith("/")) {
            throw new IllegalStateException(
                    "Integration tests require IMS_TEST_DB_URL pointing to an isolated MariaDB test"
                            + " server, ending in / without a database name. Use"
                            + " scripts/with-test-database.ps1.");
        }
    }

    @TempDir Path temp;
    static final String TOKEN = "integration-test-token-1234";
    Repository repository;
    String database, url;
    String user = System.getenv("IMS_TEST_DB_USER"),
            password = System.getenv("IMS_TEST_DB_PASSWORD");

    @BeforeEach
    void setup() throws Exception {
        database = "databridge_test_" + UUID.randomUUID().toString().replace("-", "");
        String base = System.getenv("IMS_TEST_DB_URL");
        if (!base.endsWith("/"))
            throw new IllegalArgumentException(
                    "IMS_TEST_DB_URL must end in / and point to a test server without a database"
                            + " name");
        try (var db = DriverManager.getConnection(base, user, password);
                var statement = db.createStatement()) {
            statement.execute(
                    "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        }
        url = base + database;
        repository = new Repository(url, user, password);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (database != null && database.matches("databridge_test_[0-9a-f]{32}")) {
            try (var db =
                            DriverManager.getConnection(
                                    System.getenv("IMS_TEST_DB_URL"), user, password);
                    var statement = db.createStatement()) {
                statement.execute("DROP DATABASE " + database);
            }
        }
    }

    Event event(String time, int count, Boolean running) {
        return new Event(
                "1.0",
                UUID.randomUUID().toString(),
                "company",
                "input",
                "text",
                "EQ01",
                time,
                time,
                Map.of(
                        "production_count",
                        new Event.Measurement(count, "counter", "ea"),
                        "running",
                        new Event.Measurement(running, "status", null)),
                Map.of(),
                null);
    }

    Event event(int second, int count, Boolean running) {
        return event(
                "2026-09-22T09:00:" + String.format("%02d", second) + "+09:00", count, running);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"txt", "xls", "xlsx"})
    void filesStoreNamedPayloadWithSeparateCodeCommentAndExtension(String extension)
            throws Exception {
        var config = new CollectorConfig();
        var source = new CollectorConfig.Source();
        source.id = "input";
        source.comCd = "company";
        source.equipmentId = "EQ01";
        source.type = extension.equals("txt") ? "text" : "excel";
        source.directory = ".";
        source.glob = "*." + extension;
        source.settleSeconds = 0;
        var field = new CollectorConfig.Field();
        field.column = 1;
        field.headerName = "값";
        source.payload.put("value", field);
        var second = new CollectorConfig.Field();
        second.column = 4;
        second.headerName = "값2";
        source.payload.put("value2", second);
        var note = new CollectorConfig.Field();
        note.column = 2;
        note.type = "str";
        note.target = "cmnt";
        source.fields.put("cmnt", note);
        var code = new CollectorConfig.Field();
        code.column = 3;
        code.type = "str";
        code.target = "item_code";
        source.fields.put("item_code", code);
        config.sources = List.of(source);
        Path file = temp.resolve("sample." + extension);
        if (extension.equals("txt"))
            Files.writeString(file, "값\t비고\t항목코드\t값2\t미선택\n20\t첫 번째 테스트\tTEXT_002\t30\t버림\n");
        else
            try (var book =
                            extension.equals("xls")
                                    ? new org.apache.poi.hssf.usermodel.HSSFWorkbook()
                                    : new XSSFWorkbook();
                    var out = Files.newOutputStream(file)) {
                var sheet = book.createSheet();
                var header = sheet.createRow(0);
                for (int i = 0; i < 4; i++)
                    header.createCell(i).setCellValue(List.of("값", "비고", "항목코드", "값2").get(i));
                var row = sheet.createRow(1);
                row.createCell(0).setCellValue(20);
                row.createCell(1).setCellValue("첫 번째 테스트");
                row.createCell(2).setCellValue("TEXT_002");
                row.createCell(3).setCellValue(30);
                row.createCell(4).setCellValue("버림");
                book.write(out);
            }
        var ims = new ImsConfig();
        ims.port = 0;
        try (var server = new ImsServer(ims, repository, TOKEN);
                var client = HttpClient.newHttpClient()) {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            config.endpoint = base + "/api/v1/events";
            try (var sender = new Sender(config, TOKEN)) {
                assertEquals(
                        1,
                        new SourceCollector(
                                        config,
                                        source,
                                        temp,
                                        new Progress(temp.resolve("progress.json")),
                                        sender)
                                .poll());
            }
            var row =
                    Json.MAPPER
                            .readTree(get(client, base, "data-json").body())
                            .path("items")
                            .get(0);
            assertEquals("sample." + extension, row.path("file_name").asText());
            assertEquals(extension, row.path("file_extension").asText());
            var payload = row.path("payload");
            assertEquals(2, payload.size());
            assertEquals(Json.MAPPER.valueToTree(Map.of("값", "20", "값2", "30")), payload);
            assertEquals("TEXT_002", row.path("item_code").asText());
            assertEquals("첫 번째 테스트", row.path("cmnt").asText());
            assertFalse(payload.has("eventId"));
            assertFalse(payload.has("schemaVersion"));
            assertFalse(payload.has("measurements"));
            assertTrue(Files.exists(temp.resolve("complete/sample." + extension)));
        }
    }

    @Test
    void identicalFileValuesCannotReuseAnEventIdForAnotherSource() throws Exception {
        var original = event(0, 100, true);
        repository.ingest(original);
        var changed =
                new Event(
                        original.schemaVersion(),
                        original.eventId(),
                        original.comCd(),
                        "other",
                        original.sourceType(),
                        original.equipmentId(),
                        original.observedAt(),
                        original.collectedAt(),
                        original.measurements(),
                        original.context(),
                        original.origin(),
                        original.fileData());
        assertThrows(Repository.Conflict.class, () -> repository.ingest(changed));
        assertEquals(1, repository.listJsonData(null, null, null, 0, 100).size());
    }

    @Test
    void originalFileNameSurvivesRestartAndCannotChangeOnReplay() throws Exception {
        var original = event(0, 100, true);
        String fileName = "설비 A (1).TXT";
        var file =
                new Event(
                        original.schemaVersion(),
                        original.eventId(),
                        original.comCd(),
                        original.sourceId(),
                        original.sourceType(),
                        original.equipmentId(),
                        original.observedAt(),
                        original.collectedAt(),
                        original.measurements(),
                        original.context(),
                        new Event.Origin(fileName, 2L));
        assertEquals("accepted", repository.ingest(file));
        var row = repository.listJsonData(null, null, null, 0, 100).getFirst();
        assertEquals(fileName, row.get("file_name"));
        assertEquals("txt", row.get("file_extension"));
        assertEquals(Json.MAPPER.valueToTree(file.filePayload()), row.get("payload"));
        assertEquals("duplicate", new Repository(url, user, password).ingest(file));
        var changed =
                new Event(
                        file.schemaVersion(),
                        file.eventId(),
                        file.comCd(),
                        file.sourceId(),
                        file.sourceType(),
                        file.equipmentId(),
                        file.observedAt(),
                        file.collectedAt(),
                        file.measurements(),
                        file.context(),
                        new Event.Origin("another.txt", 2L));
        assertThrows(Repository.Conflict.class, () -> repository.ingest(changed));
    }

    @Test
    void requestsWithoutFileNameRemainCompatibleAndFileNameLengthIsValidated() throws Exception {
        var original = event(0, 100, true);
        assertEquals("accepted", repository.ingest(original));
        assertNull(repository.listJsonData(null, null, null, 0, 100).getFirst().get("file_name"));
        assertEquals("duplicate", repository.ingest(original));
        assertDoesNotThrow(() -> new Event.Origin("한".repeat(251) + ".txt", 1L));
        assertThrows(IllegalArgumentException.class, () -> new Event.Origin("x".repeat(256), 1L));
        assertThrows(IllegalArgumentException.class, () -> new Event.Origin(" ", 1L));
    }

    @Test
    void reusedFileEventIdCannotChangeCodeOrComment() throws Exception {
        var event = event(0, 100, true);
        var file =
                new Event(
                        event.schemaVersion(),
                        event.eventId(),
                        event.comCd(),
                        event.sourceId(),
                        event.sourceType(),
                        event.equipmentId(),
                        event.observedAt(),
                        event.collectedAt(),
                        event.measurements(),
                        event.context(),
                        event.origin(),
                        new Event.FileData(Map.of("값", "20"), "TEXT_002", "테스트"));
        assertEquals("accepted", repository.ingest(file));
        assertEquals("duplicate", repository.ingest(file));
        for (var changed :
                List.of(
                        new Event.FileData(Map.of("값", "20"), "OTHER", "테스트"),
                        new Event.FileData(Map.of("값", "20"), "TEXT_002", "변경"))) {
            var retry =
                    new Event(
                            file.schemaVersion(),
                            file.eventId(),
                            file.comCd(),
                            file.sourceId(),
                            file.sourceType(),
                            file.equipmentId(),
                            file.observedAt(),
                            file.collectedAt(),
                            file.measurements(),
                            file.context(),
                            file.origin(),
                            changed);
            assertThrows(Repository.Conflict.class, () -> repository.ingest(retry));
        }
        var row = repository.listJsonData(null, null, null, 0, 100).getFirst();
        assertEquals("TEXT_002", row.get("item_code"));
        assertEquals("테스트", row.get("cmnt"));
    }

    @Test
    void concurrentDeliveryAndRestartAreIdempotent() throws Exception {
        var event = event(0, 100, true);
        var results = new ArrayList<Future<String>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 10; i++)
                results.add(executor.submit(() -> repository.ingest(event)));
            int accepted = 0;
            for (var result : results)
                if (result.get(10, TimeUnit.SECONDS).equals("accepted")) accepted++;
            assertEquals(1, accepted);
        }
        var restarted = new Repository(url, user, password);
        assertEquals("duplicate", restarted.ingest(event));
        assertEquals(1, restarted.listJsonData(null, null, null, 0, 100).size());
    }

    @Test
    void apiAuthenticationValidationAndLimits() throws Exception {
        var config = new ImsConfig();
        config.port = 0;
        config.maxBodyBytes = 1024;
        try (var server = new ImsServer(config, repository, TOKEN);
                var client = HttpClient.newHttpClient()) {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            var event = event(0, 100, true);
            assertEquals(
                    200,
                    client.send(
                                    HttpRequest.newBuilder(URI.create(base + "/health"))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .statusCode());
            assertEquals(
                    401,
                    client.send(
                                    HttpRequest.newBuilder(URI.create(base + "/api/v1/events"))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .statusCode());
            assertEquals(400, post(client, base, Json.write(event), "wrong").statusCode());
            assertEquals(400, post(client, base, "{}", event.eventId()).statusCode());
            assertEquals(400, post(client, base, "null", event.eventId()).statusCode());
            assertEquals(413, post(client, base, " ".repeat(1100), event.eventId()).statusCode());
            var missingCompany = Json.MAPPER.valueToTree(event);
            ((com.fasterxml.jackson.databind.node.ObjectNode) missingCompany).remove("com_cd");
            assertEquals(
                    400,
                    post(client, base, Json.write(missingCompany), event.eventId()).statusCode());
            assertEquals(
                    400,
                    post(
                                    client,
                                    base,
                                    Json.write(event).replace("com_cd", "siteId"),
                                    event.eventId())
                            .statusCode());
            assertEquals(200, post(client, base, Json.write(event), event.eventId()).statusCode());
            assertEquals(
                    "duplicate",
                    Json.read(
                                    post(client, base, Json.write(event), event.eventId()).body(),
                                    Event.Ack.class)
                            .status());
            var invalidQuery =
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/events?limit=1001"))
                            .header("Authorization", "Bearer " + TOKEN)
                            .GET()
                            .build();
            assertEquals(
                    400,
                    client.send(invalidQuery, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test
    void textAndExcelBatchesRetryWholeFilesAfterUnavailableHub() throws Exception {
        var config = new CollectorConfig();
        config.rootDir = ".";
        var text = new CollectorConfig.Source();
        text.id = "text";
        text.comCd = "company";
        text.type = "text";
        text.equipmentId = "EQ-TXT";
        text.directory = ".";
        text.glob = "*.txt";
        text.skipLines = 0;
        text.settleSeconds = 0;
        var field = new CollectorConfig.Field();
        field.column = 1;
        text.fields.put("temperature", field);
        var excel = new CollectorConfig.Source();
        excel.id = "excel";
        excel.comCd = "company02";
        excel.type = "excel";
        excel.equipmentId = "EQ-XLS";
        excel.directory = ".";
        excel.glob = "*.xlsx";
        excel.firstDataRow = 1;
        excel.settleSeconds = 0;
        excel.fields.put("temperature", field);
        config.sources = List.of(text, excel);
        Files.writeString(temp.resolve("data.txt"), "10\n20\n");
        try (var book = new XSSFWorkbook();
                var out = Files.newOutputStream(temp.resolve("data.xlsx"))) {
            book.createSheet().createRow(0).createCell(0).setCellValue(30);
            book.write(out);
        }
        var ims = new ImsConfig();
        ims.port = 0;
        try (var server = new ImsServer(ims, repository, TOKEN)) {
            try (var unavailable =
                    new java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
                config.endpoint =
                        "http://127.0.0.1:" + unavailable.getLocalPort() + "/api/v1/events";
            }
            config.timeoutSeconds = 1;
            var progress = new Progress(temp.resolve("progress.json"));
            // 닫힌 포트로는 요청이 전달되지 않으며, 파일 처리 위치도 갱신되지 않습니다.
            try (var sender = new Sender(config, TOKEN)) {
                assertThrows(
                        Exception.class,
                        () -> new SourceCollector(config, text, temp, progress, sender).poll());
                assertThrows(
                        Exception.class,
                        () -> new SourceCollector(config, excel, temp, progress, sender).poll());
            }
            assertEquals(0, progress.status().get("legacyRowCheckpoints"));
            Files.move(temp.resolve("error/data.txt"), temp.resolve("data.txt"));
            Files.move(temp.resolve("error/data.xlsx"), temp.resolve("data.xlsx"));
            Files.move(
                    temp.resolve("error/data.txt.retry.json"), temp.resolve("data.txt.retry.json"));
            Files.move(
                    temp.resolve("error/data.xlsx.retry.json"),
                    temp.resolve("data.xlsx.retry.json"));
            server.start();
            config.endpoint = "http://127.0.0.1:" + server.port() + "/api/v1/events";
            var restarted = new Progress(temp.resolve("progress.json"));
            try (var sender = new Sender(config, TOKEN)) {
                assertEquals(2, new SourceCollector(config, text, temp, restarted, sender).poll());
                assertEquals(1, new SourceCollector(config, excel, temp, restarted, sender).poll());
                var again = new Progress(temp.resolve("progress.json"));
                assertEquals(0, new SourceCollector(config, text, temp, again, sender).poll());
                assertEquals(0, new SourceCollector(config, excel, temp, again, sender).poll());
            }
            assertEquals(3, repository.listJsonData(null, null, null, 0, 100).size());
            assertEquals(2, tableCount("file_import"));
            assertEquals(2, repository.listJsonData("company", "EQ-TXT", null, 0, 100).size());
            assertEquals(1, repository.listJsonData("company02", "EQ-XLS", "excel", 0, 100).size());
            assertEquals(2, repository.listJsonData(null, null, "text", 0, 100).size());
            assertTrue(repository.listJsonData(null, null, "modbus_tcp", 0, 100).isEmpty());
        }
    }

    @Test
    void modbusCliStoresOnlyProductionStateAndRunWithNoJsonCopy() throws Exception {
        var ims = new ImsConfig();
        ims.port = 0;
        try (var server = new ImsServer(ims, repository, TOKEN);
                var plc = new java.net.ServerSocket(0, 2, InetAddress.getLoopbackAddress());
                var executor = Executors.newSingleThreadExecutor();
                var client = HttpClient.newHttpClient()) {
            plc.setSoTimeout(10000);
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            var simulated =
                    executor.submit(
                            () -> {
                                for (int block = 0; block < 2; block++) {
                                    try (var connection = plc.accept()) {
                                        var input =
                                                new java.io.DataInputStream(
                                                        connection.getInputStream());
                                        int transaction = input.readUnsignedShort();
                                        assertEquals(0, input.readUnsignedShort());
                                        assertEquals(6, input.readUnsignedShort());
                                        int unit = input.readUnsignedByte(),
                                                function = input.readUnsignedByte();
                                        assertEquals(block + 1, unit);
                                        assertEquals(block == 0 ? 3 : 4, function);
                                        assertEquals(
                                                block == 0 ? 1 : 40, input.readUnsignedShort());
                                        int[] values =
                                                block == 0
                                                        ? new int[] {1, 32768, 65535}
                                                        : new int[] {123, 456};
                                        assertEquals(values.length, input.readUnsignedShort());
                                        var output =
                                                new java.io.DataOutputStream(
                                                        connection.getOutputStream());
                                        output.writeShort(transaction);
                                        output.writeShort(0);
                                        output.writeShort(3 + values.length * 2);
                                        output.writeByte(unit);
                                        output.writeByte(function);
                                        output.writeByte(values.length * 2);
                                        for (int value : values) output.writeShort(value);
                                        output.flush();
                                    }
                                }
                                return true;
                            });
            Path common = temp.resolve("collector.json");
            Files.writeString(
                    common,
                    Json.write(
                            Map.of(
                                    "sourceFile",
                                    "test-pc.json",
                                    "rootDir",
                                    temp.toString(),
                                    "endpoint",
                                    base + "/api/v1/events")));
            Files.writeString(
                    temp.resolve("test-pc.json"),
                    """
                    {"collectionType":"modbus_tcp","nodes":[{"nodeId":"ND01","com_cd":"company","host":"127.0.0.1","counterWordOrder":"LOW_HIGH","port":%d,"readBlocks":[
                      {"startAddress":1,"registerCount":3}, {"unitId":2,"functionCode":4,"startAddress":40,"registerCount":2}
                    ]}]}
                    """
                            .formatted(plc.getLocalPort()));
            Files.writeString(temp.resolve("ignored.json"), "Other PC settings must never be read");
            Path collectorJar =
                    Path.of("..", "data-collector", "target", "data-collector.jar")
                            .toAbsolutePath();
            Path log = temp.resolve("collector.log");
            var builder =
                    new ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "-Dfile.encoding=UTF-8",
                            "-Dsun.stdout.encoding=UTF-8",
                            "-Dsun.stderr.encoding=UTF-8",
                            "-Djdk.net.unixdomain.tmpdir=" + Path.of("target").toAbsolutePath(),
                            "-jar",
                            collectorJar.toString(),
                            "once",
                            "--config",
                            common.toString());
            Files.writeString(temp.resolve("application.properties"), "ims.token=" + TOKEN + "\n");
            var process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Collector CLI timed out");
                assertEquals(0, process.exitValue(), Files.readString(log));
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
            assertTrue(simulated.get(5, TimeUnit.SECONDS));
            assertTrue(repository.listJsonData("company", "ND01", null, 0, 100).isEmpty());
            var status = repository.listStatus("company", "ND01", null, 0, 100).getFirst();
            assertEquals(4294934528L, ((Number) status.get("counter_value")).longValue());
            assertEquals(1, ((Number) status.get("status")).intValue());
            assertEquals(1, repository.listRuns("company", "ND01", null, 0, 100).size());
            assertFalse(status.containsKey("last_event_hash"));
            assertFalse(status.containsKey("last_data_id"));
            var packet =
                    new ModbusData(
                            UUID.randomUUID().toString(),
                            "company",
                            "ND02",
                            List.of(
                                    new ModbusData.Data(
                                            "2026-09-22T09:00:00.987654321+09:00",
                                            1,
                                            3,
                                            1,
                                            List.of(1, 0, 42),
                                            1,
                                            "HIGH_LOW")));
            assertEquals(
                    "accepted",
                    Json.read(
                                    post(client, base, Json.write(packet), packet.eventId()).body(),
                                    Event.Ack.class)
                            .status());
            assertEquals(
                    "duplicate",
                    Json.read(
                                    post(client, base, Json.write(packet), packet.eventId()).body(),
                                    Event.Ack.class)
                            .status());
            assertEquals("duplicate", new Repository(url, user, password).ingest(packet));
            assertEquals(
                    409,
                    post(client, base, Json.write(packet).replace("42", "43"), packet.eventId())
                            .statusCode());
            assertEquals(
                    400,
                    post(client, base, Json.write(packet).replace("42", "42.5"), packet.eventId())
                            .statusCode());
            assertEquals(
                    400,
                    post(client, base, Json.write(packet).replace("42", "65536"), packet.eventId())
                            .statusCode());
            assertTrue(repository.listJsonData(null, null, null, 0, 100).isEmpty());
            assertFalse(Files.exists(temp.resolve("runtime/progress.json")));
        }
    }

    @Test
    void paginationAndCompanyFiltering() throws Exception {
        repository.ingest(event(0, 1, true));
        repository.ingest(event(1, 2, true));
        var first = repository.listJsonData("company", null, null, 0, 1);
        assertEquals(1, first.size());
        assertEquals(
                1,
                repository
                        .listJsonData(
                                "company",
                                null,
                                null,
                                ((Number) first.getFirst().get("id")).longValue(),
                                1)
                        .size());
        assertEquals(0, repository.listJsonData("missing", null, null, 0, 100).size());
    }

    @Test
    void sameEventIdIsScopedByCompanyAndConflictingContentDoesNotOverwrite() throws Exception {
        var first = event(0, 100, true);
        assertEquals("accepted", repository.ingest(first));
        assertEquals("duplicate", repository.ingest(first));
        var changed =
                new Event(
                        first.schemaVersion(),
                        first.eventId(),
                        first.comCd(),
                        first.sourceId(),
                        first.sourceType(),
                        first.equipmentId(),
                        first.observedAt(),
                        first.collectedAt(),
                        Map.of("value", new Event.Measurement(999, "sensor", null)),
                        Map.of(),
                        null);
        assertThrows(Repository.Conflict.class, () -> repository.ingest(changed));
        var otherCompany =
                new Event(
                        first.schemaVersion(),
                        first.eventId(),
                        "company02",
                        first.sourceId(),
                        first.sourceType(),
                        first.equipmentId(),
                        first.observedAt(),
                        first.collectedAt(),
                        first.measurements(),
                        first.context(),
                        first.origin());
        assertEquals("accepted", repository.ingest(otherCompany));
        var rows = repository.listJsonData("company", null, null, 0, 100);
        assertEquals(1, rows.size());
        assertEquals(Json.MAPPER.valueToTree(first.filePayload()), rows.getFirst().get("payload"));
        assertEquals(1, repository.listJsonData("company02", null, null, 0, 100).size());
    }

    @Test
    void modbusWithoutProductionLayoutIsRejectedWithoutStoringJson() throws Exception {
        var raw =
                new ModbusData(
                        UUID.randomUUID().toString(),
                        "company02",
                        "ND01",
                        List.of(
                                new ModbusData.Data(
                                        "2026-09-22T09:00:06.750+09:00", 1, 3, 1, List.of(123))));
        assertThrows(IllegalArgumentException.class, () -> repository.ingest(raw));
        assertTrue(repository.listJsonData(null, null, null, 0, 100).isEmpty());
        assertTrue(repository.listStatus(null, null, null, 0, 100).isEmpty());
    }

    @Test
    void utcDatesAndApiUseWholeSecondsWhilePreservingRawPayload() throws Exception {
        var original = event("2026-09-22T15:25:47.607046100+09:00", 100, true);
        repository.ingest(original);
        try (var db = DriverManager.getConnection(url, user, password);
                var s = db.createStatement()) {
            s.execute("SET time_zone='+09:00'");
            try (var r = s.executeQuery("SELECT observed_at,received_at,payload FROM data_json")) {
                assertTrue(r.next());
                assertEquals(
                        java.time.LocalDateTime.of(2026, 9, 22, 6, 25, 47),
                        r.getObject(1, java.time.LocalDateTime.class));
                assertEquals(0, r.getObject(2, java.time.LocalDateTime.class).getNano());
                assertEquals(
                        Json.MAPPER.valueToTree(original.filePayload()),
                        Json.MAPPER.readTree(r.getString(3)));
            }
        }
        var config = new ImsConfig();
        config.port = 0;
        try (var server = new ImsServer(config, repository, TOKEN);
                var client = HttpClient.newHttpClient()) {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            var response =
                    get(client, base, "events?com_cd=company&equipmentId=EQ01&collectionType=text");
            assertEquals(200, response.statusCode());
            var row = Json.MAPPER.readTree(response.body()).path("items").get(0);
            assertEquals("company", row.path("com_cd").asText());
            assertEquals("text", row.path("collection_type").asText());
            assertEquals(400, get(client, base, "events?collectionType=invalid").statusCode());
            assertTrue(row.path("id").asLong() > 0);
            assertFalse(row.has("seq"));
            assertFalse(row.has("site_id"));
            assertEquals("2026-09-22 06:25:47", row.path("observed_at").asText());
            assertTrue(
                    row.path("received_at")
                            .asText()
                            .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
            assertEquals(Json.MAPPER.valueToTree(original.filePayload()), row.path("payload"));
            var detail =
                    get(client, base, "data/" + row.path("id").asLong() + "/values?com_cd=company");
            assertEquals(404, detail.statusCode());
            assertTrue(
                    Json.MAPPER
                            .readTree(get(client, base, "events?com_cd=missing").body())
                            .path("items")
                            .isEmpty());
            for (String removed : List.of("latest", "counters", "runs"))
                assertEquals(404, get(client, base, removed).statusCode());
        }
    }

    @Test
    void newIdsPreserveRepeatedAndOutOfOrderObservations() throws Exception {
        var times =
                List.of(
                        "2026-09-22T09:00:00.900+09:00",
                        "2026-09-22T09:00:00.100+09:00",
                        "2026-09-22T09:00:00.100+09:00");
        for (var time : times) repository.ingest(event(time, 100, true));
        var rows = repository.listJsonData(null, null, null, 0, 100);
        assertEquals(3, rows.size());
        for (int i = 0; i < times.size(); i++) {
            assertEquals("2026-09-22 00:00:00", rows.get(i).get("observed_at"));
            assertFalse(
                    ((com.fasterxml.jackson.databind.JsonNode) rows.get(i).get("payload"))
                            .has("observed_at"));
        }
    }

    @Test
    void hubSeparatesCompaniesAndStoresFileJsonWhole() throws Exception {
        var first = event(0, 100, true);
        repository.ingest(first);
        var other =
                new Event(
                        first.schemaVersion(),
                        first.eventId(),
                        "company02",
                        first.sourceId(),
                        first.sourceType(),
                        first.equipmentId(),
                        first.observedAt(),
                        first.collectedAt(),
                        first.measurements(),
                        first.context(),
                        first.origin());
        repository.ingest(other);
        var one = repository.listJsonData("company", "EQ01", null, 0, 100).getFirst();
        var two = repository.listJsonData("company02", "EQ01", null, 0, 100).getFirst();
        assertNotEquals(one.get("equipment_id"), two.get("equipment_id"));
        assertEquals(Json.MAPPER.valueToTree(first.filePayload()), one.get("payload"));
        long dataId = ((Number) one.get("id")).longValue();
        try (var db = DriverManager.getConnection(url, user, password);
                var s = db.createStatement()) {
            var names = new TreeSet<String>();
            try (var r = s.executeQuery("SHOW TABLES")) {
                while (r.next()) names.add(r.getString(1));
            }
            assertEquals(
                    Set.of("equipment", "data_json", "data_modbus", "modbus_state", "file_import"),
                    names);
            assertThrows(
                    java.sql.SQLException.class,
                    () ->
                            s.executeUpdate(
                                    "UPDATE data_json SET equipment_id="
                                            + two.get("equipment_id")
                                            + " WHERE id="
                                            + dataId));
        }
        assertEquals("duplicate", repository.ingest(first));
        assertTrue(repository.listRuns(null, null, null, 0, 100).isEmpty());
        assertTrue(repository.listStatus(null, null, null, 0, 100).isEmpty());
    }

    @Test
    void distinctDeliveriesCanRegisterOneEquipmentConcurrently() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < 12; i++) {
                var value = event(i, 100 + i, true);
                futures.add(executor.submit(() -> repository.ingest(value)));
            }
            for (var future : futures) assertEquals("accepted", future.get(15, TimeUnit.SECONDS));
        }
        var rows = repository.listJsonData("company", "EQ01", "input", "text", 0, 100);
        assertEquals(12, rows.size());
        assertEquals(1, rows.stream().map(row -> row.get("equipment_id")).distinct().count());
        assertTrue(repository.listRuns(null, null, null, 0, 100).isEmpty());
    }

    @Test
    void productionFailureRollsBackWholeDeliveryAndRegistration() throws Exception {
        var packet = production("company", 0, 1, 100, "HIGH_LOW");
        try (var db = DriverManager.getConnection(url, user, password);
                var s = db.createStatement()) {
            s.execute(
                    "CREATE TRIGGER fail_state BEFORE INSERT ON modbus_state FOR EACH ROW SIGNAL"
                            + " SQLSTATE '45000' SET MESSAGE_TEXT='test rollback'");
            assertThrows(java.sql.SQLException.class, () -> repository.ingest(packet));
            for (var table : List.of("equipment", "data_json", "data_modbus", "modbus_state")) {
                try (var r = s.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
                    assertTrue(r.next());
                    assertEquals(0, r.getInt(1));
                }
            }
            s.execute("DROP TRIGGER fail_state");
        }
        assertEquals("accepted", repository.ingest(packet));
        assertEquals(1, repository.listRuns(null, null, null, 0, 100).size());
    }

    @Test
    void productionCyclesUseDeltasAndZeroAtRestPreservesQuantity() throws Exception {
        repository.ingest(production("company", 0, 1, 100, "HIGH_LOW"));
        repository.ingest(production("company", 5, 1, 110, "HIGH_LOW"));
        repository.ingest(production("company", 10, 1, 115, "HIGH_LOW"));
        var stopped = production("company", 15, 0, 0, "HIGH_LOW");
        repository.ingest(stopped);
        assertEquals("duplicate", repository.ingest(stopped));
        var runs = repository.listRuns("company", "ND01", null, 0, 100);
        assertEquals(1, runs.size());
        assertEquals(15L, ((Number) runs.getFirst().get("production_count")).longValue());
        assertEquals("completed", runs.getFirst().get("run_state"));
        assertEquals("2026-09-22 00:00:15", runs.getFirst().get("ended_at"));
        assertEquals(15, ((Number) runs.getFirst().get("observed_seconds")).intValue());
        repository.ingest(production("company", 25, 1, 3, "HIGH_LOW"));
        repository.ingest(production("company", 30, 1, 8, "HIGH_LOW"));
        repository.ingest(production("company", 35, 0, 0, "HIGH_LOW"));
        runs = repository.listRuns("company", null, null, 0, 100);
        assertEquals(2, runs.size());
        assertEquals(8L, ((Number) runs.get(1).get("production_count")).longValue());
        assertEquals(
                0L,
                ((Number)
                                repository
                                        .listStatus("company", null, null, 0, 100)
                                        .getFirst()
                                        .get("counter_value"))
                        .longValue());
        assertTrue(repository.listJsonData(null, null, null, 0, 100).isEmpty());
    }

    @Test
    void wordOrdersUnsignedRangeAndCompanyIsolation() throws Exception {
        for (String company : List.of("high", "low")) {
            String order = company.equals("high") ? "HIGH_LOW" : "LOW_HIGH";
            repository.ingest(production(company, 0, 1, 2147483648L, order));
            repository.ingest(production(company, 5, 1, 4294967295L, order));
            var state = repository.listStatus(company, "ND01", null, 0, 100).getFirst();
            assertEquals(4294967295L, ((Number) state.get("counter_value")).longValue());
            assertEquals(
                    2147483647L,
                    ((Number)
                                    repository
                                            .listRuns(company, null, null, 0, 100)
                                            .getFirst()
                                            .get("production_count"))
                            .longValue());
        }
        assertEquals(2, repository.listRuns(null, null, null, 0, 100).size());
    }

    @Test
    void outOfOrderSubsecondSamplesAndConcurrentDuplicatesDoNotRecount() throws Exception {
        repository.ingest(productionAt("company", "2026-09-22T00:00:00.100Z", 1, 10, "HIGH_LOW"));
        var latest = productionAt("company", "2026-09-22T00:00:00.900Z", 1, 15, "HIGH_LOW");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < 8; i++)
                futures.add(executor.submit(() -> repository.ingest(latest)));
            int accepted = 0;
            for (var f : futures) if (f.get(15, TimeUnit.SECONDS).equals("accepted")) accepted++;
            assertEquals(1, accepted);
        }
        repository.ingest(productionAt("company", "2026-09-22T00:00:00.500Z", 0, 0, "HIGH_LOW"));
        repository.ingest(productionAt("company", "2026-09-22T00:00:00.900Z", 0, 0, "HIGH_LOW"));
        assertTrue(repository.listJsonData(null, null, null, 0, 100).isEmpty());
        var run = repository.listRuns(null, null, null, 0, 100).getFirst();
        assertEquals("running", run.get("run_state"));
        assertNull(run.get("ended_at"));
        assertEquals(5L, ((Number) run.get("production_count")).longValue());
    }

    @Test
    void gapsUnknownStatusAndMappingChangesDoNotInventStopTimesOrProduction() throws Exception {
        repository.ingest(production("company", 0, 1, 100, "HIGH_LOW"));
        repository.ingest(production("company", 5, 1, 105, "HIGH_LOW"));
        repository.ingest(production("company", 30, 1, 500, "HIGH_LOW"));
        repository.ingest(production("company", 35, 1, 503, "HIGH_LOW"));
        repository.ingest(production("company", 40, 99, 700, "HIGH_LOW"));
        repository.ingest(production("company", 45, 1, 800, "HIGH_LOW"));
        repository.ingest(production("company", 50, 1, 900, "LOW_HIGH"));
        var runs = repository.listRuns("company", null, null, 0, 100);
        assertEquals(4, runs.size());
        for (int i = 0; i < 3; i++) {
            assertEquals("interrupted", runs.get(i).get("run_state"));
            assertNull(runs.get(i).get("ended_at"));
            assertEquals(
                    List.of("connection_gap", "invalid_status", "mapping_changed").get(i),
                    runs.get(i).get("end_reason"));
        }
        assertEquals(5L, ((Number) runs.get(0).get("production_count")).longValue());
        assertEquals(3L, ((Number) runs.get(1).get("production_count")).longValue());
        assertEquals(0L, ((Number) runs.get(3).get("production_count")).longValue());
    }

    @Test
    void resetWhileProducingSplitsRunsAndPreservesTotalQuantity() throws Exception {
        repository.ingest(production("company", 0, 1, 65530, "HIGH_LOW"));
        repository.ingest(
                production("company", 5, 1, 65540, "HIGH_LOW")); // 하위 워드의 범위 초과는 카운터 초기화가 아닙니다.
        repository.ingest(production("company", 10, 1, 3, "HIGH_LOW")); // 실제 카운터 초기화입니다.
        repository.ingest(production("company", 15, 1, 8, "HIGH_LOW"));
        repository.ingest(production("company", 20, 0, 10, "HIGH_LOW"));
        var runs = repository.listRuns("company", null, null, 0, 100);
        assertEquals(2, runs.size());
        assertEquals("counter_reset", runs.get(0).get("end_reason"));
        assertEquals("interrupted", runs.get(0).get("run_state"));
        assertNull(runs.get(0).get("ended_at"));
        assertEquals(10L, ((Number) runs.get(0).get("production_count")).longValue());
        assertEquals(10L, ((Number) runs.get(1).get("production_count")).longValue());
        assertEquals("completed", runs.get(1).get("run_state"));
        assertEquals("2026-09-22 00:00:20", runs.get(1).get("ended_at"));
        assertEquals(
                20L,
                runs.stream()
                        .mapToLong(run -> ((Number) run.get("production_count")).longValue())
                        .sum());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "HIGH_LOW,0",
        "HIGH_LOW,3",
        "LOW_HIGH,0",
        "LOW_HIGH,3"
    })
    void quickCounterRestartClosesPreviousRunAndKeepsNewQuantitySeparate(
            String order, long firstCounter) throws Exception {
        repository.ingest(production("company", 0, 1, 100, order));
        repository.ingest(production("company", 5, 1, 110, order));
        repository.ingest(production("other", 0, 1, 500, order));
        // 저장된 상태에서 마지막 생산 구간과 누적값을 읽는지 확인하도록 Repository도 다시 생성합니다.
        repository = new Repository(url, user, password);
        var restarted = production("company", 7, 1, firstCounter, order);
        assertEquals("accepted", repository.ingest(restarted));
        assertEquals("duplicate", repository.ingest(restarted));
        var runs = repository.listRuns("company", null, null, 0, 100);
        assertEquals(2, runs.size());
        var old = runs.get(0);
        var current = runs.get(1);
        assertEquals("interrupted", old.get("run_state"));
        assertEquals("counter_reset", old.get("end_reason"));
        assertNull(old.get("ended_at"));
        assertEquals("2026-09-22 00:00:05", old.get("last_observed_at"));
        assertEquals(5L, ((Number) old.get("observed_seconds")).longValue());
        assertEquals(10L, ((Number) old.get("production_count")).longValue());
        assertEquals("running", current.get("run_state"));
        assertNull(current.get("ended_at"));
        assertEquals("2026-09-22 00:00:07", current.get("started_at"));
        assertEquals(firstCounter, ((Number) current.get("production_count")).longValue());
        var state = repository.listStatus("company", null, null, 0, 100).getFirst();
        assertEquals(current.get("id"), state.get("current_run_id"));
        assertEquals(firstCounter, ((Number) state.get("counter_value")).longValue());
        // 초기화 전 패킷이 늦게 도착해도 이전 생산 구간을 다시 열거나 수량을 더하지 않아야 합니다.
        repository.ingest(production("company", 6, 1, 115, order));
        repository.ingest(production("company", 9, 1, firstCounter + 4, order));
        repository.ingest(production("company", 14, 0, firstCounter + 6, order));
        runs = repository.listRuns("company", null, null, 0, 100);
        assertEquals(2, runs.size());
        assertEquals(old, runs.get(0));
        assertEquals("completed", runs.get(1).get("run_state"));
        assertEquals("2026-09-22 00:00:14", runs.get(1).get("ended_at"));
        assertEquals(firstCounter + 6, ((Number) runs.get(1).get("production_count")).longValue());
        assertEquals(
                "running",
                repository.listRuns("other", null, null, 0, 100).getFirst().get("run_state"));
    }

    @Test
    void restartAfterLongGapClosesOldRunAtItsLastConfirmedObservation() throws Exception {
        repository.ingest(production("company", 0, 1, 100, "HIGH_LOW"));
        repository.ingest(production("company", 5, 1, 110, "HIGH_LOW"));
        repository.ingest(production("company", 30, 1, 0, "HIGH_LOW"));
        repository.ingest(production("company", 35, 1, 4, "HIGH_LOW"));
        var runs = repository.listRuns("company", null, null, 0, 100);
        assertEquals(2, runs.size());
        assertEquals("interrupted", runs.get(0).get("run_state"));
        assertEquals("connection_gap", runs.get(0).get("end_reason"));
        assertNull(runs.get(0).get("ended_at"));
        assertEquals("2026-09-22 00:00:05", runs.get(0).get("last_observed_at"));
        assertEquals(10L, ((Number) runs.get(0).get("production_count")).longValue());
        assertEquals("running", runs.get(1).get("run_state"));
        assertEquals(4L, ((Number) runs.get(1).get("production_count")).longValue());
    }

    @Test
    void independentConcurrentProductionSamplesSerializePerSource() throws Exception {
        repository.ingest(production("company", 0, 1, 0, "HIGH_LOW"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<String>>();
            for (int i = 1; i <= 12; i++) {
                var packet = production("company", i, 1, i * 10, "HIGH_LOW");
                futures.add(executor.submit(() -> repository.ingest(packet)));
            }
            for (var future : futures)
                assertTrue(
                        Set.of("accepted", "ignored_stale")
                                .contains(future.get(15, TimeUnit.SECONDS)));
        }
        var runs = repository.listRuns(null, null, null, 0, 100);
        assertEquals(1, runs.size());
        assertEquals(120L, ((Number) runs.getFirst().get("production_count")).longValue());
    }

    @Test
    void monitoringApiFiltersCompaniesAndReportsLostConnectionWithoutFalseStop() throws Exception {
        repository.ingest(production("company", 0, 1, 10, "LOW_HIGH"));
        repository.ingest(production("company", 5, 1, 20, "LOW_HIGH"));
        repository.ingest(production("company02", 0, 1, 500, "HIGH_LOW"));
        var config = new ImsConfig();
        config.port = 0;
        try (var server = new ImsServer(config, repository, TOKEN);
                var client = HttpClient.newHttpClient()) {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            var response =
                    get(client, base, "modbus/runs?com_cd=company&equipmentId=ND01&sourceId=ND01");
            assertEquals(200, response.statusCode());
            var items = Json.MAPPER.readTree(response.body()).path("items");
            assertEquals(1, items.size());
            assertEquals(10, items.get(0).path("production_count").asInt());
            assertEquals(400, get(client, base, "modbus/runs?limit=1001").statusCode());
            try (var db = DriverManager.getConnection(url, user, password);
                    var s = db.createStatement()) {
                s.execute(
                        "UPDATE modbus_state SET received_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 30"
                                + " SECOND) WHERE com_cd='company'");
            }
            var status =
                    Json.MAPPER
                            .readTree(get(client, base, "modbus/status?com_cd=company").body())
                            .path("items")
                            .get(0);
            assertEquals("disconnected", status.path("production_status").asText());
            assertFalse(status.path("connected").asBoolean());
            assertEquals(1, status.path("status").asInt());
            repository.ingest(production("company", 10, 1, 1000, "LOW_HIGH"));
            var runs = repository.listRuns("company", null, null, 0, 100);
            assertEquals(2, runs.size());
            assertNull(runs.getFirst().get("ended_at"));
            assertEquals(0L, ((Number) runs.get(1).get("production_count")).longValue());
        }
    }

    @Test
    void restRetainsCumulativeCountAndResumeContinuesTheSameCounter() throws Exception {
        repository.ingest(production("company", 0, 1, 100, "HIGH_LOW"));
        repository.ingest(production("company", 5, 1, 110, "HIGH_LOW"));
        repository.ingest(production("company", 10, 1, 115, "HIGH_LOW"));
        repository.ingest(production("company", 15, 0, 118, "HIGH_LOW"));
        repository.ingest(production("company", 20, 0, 118, "HIGH_LOW"));
        var state = repository.listStatus("company", null, null, 0, 100).getFirst();
        assertEquals(118L, ((Number) state.get("counter_value")).longValue());
        assertEquals("stopped", state.get("production_status"));
        repository.ingest(production("company", 25, 1, 122, "HIGH_LOW"));
        repository.ingest(production("company", 30, 1, 125, "HIGH_LOW"));
        repository.ingest(production("company", 35, 0, 128, "HIGH_LOW"));
        var runs = repository.listRuns("company", null, null, 0, 100);
        assertEquals(2, runs.size());
        assertEquals(18L, ((Number) runs.get(0).get("production_count")).longValue());
        assertEquals(10L, ((Number) runs.get(1).get("production_count")).longValue());
        assertEquals("2026-09-22 00:00:15", runs.get(0).get("ended_at"));
        assertEquals("2026-09-22 00:00:25", runs.get(1).get("started_at"));
        assertEquals(
                128L,
                ((Number)
                                repository
                                        .listStatus("company", null, null, 0, 100)
                                        .getFirst()
                                        .get("counter_value"))
                        .longValue());
    }

    @Test
    void oldModbusReplayDoesNotRecountAfterRecentDeliveryHasAdvanced() throws Exception {
        var first = production("company", 0, 1, 10, "HIGH_LOW");
        repository.ingest(first);
        var latest = production("company", 5, 1, 20, "HIGH_LOW");
        repository.ingest(latest);
        assertEquals("ignored_stale", new Repository(url, user, password).ingest(first));
        assertEquals("duplicate", repository.ingest(latest));
        var runs = repository.listRuns("company", null, null, 0, 100);
        assertEquals(1, runs.size());
        assertEquals(10L, ((Number) runs.getFirst().get("production_count")).longValue());
        assertTrue(repository.listJsonData(null, null, null, 0, 100).isEmpty());
    }

    @Test
    void futureObservationCannotCreateOrPoisonState() throws Exception {
        var now = java.time.Instant.parse("2026-09-22T00:00:00Z");
        var timed =
                new Repository(
                        url,
                        user,
                        password,
                        false,
                        15,
                        java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
        var error =
                assertThrows(
                        ValidationException.class,
                        () -> timed.ingest(production("company", 61, 1, 100, "HIGH_LOW")));
        assertEquals("FUTURE_OBSERVATION", error.code());
        assertEquals("datas[0].time", error.field());
        assertTrue(timed.listRuns(null, null, null, 0, 100).isEmpty());
        assertTrue(timed.listStatus(null, null, null, 0, 100).isEmpty());
        try (var db = DriverManager.getConnection(url, user, password);
                var s = db.createStatement();
                var rows = s.executeQuery("SELECT COUNT(*) FROM equipment")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
        assertEquals("accepted", timed.ingest(production("company", 5, 1, 10, "HIGH_LOW")));
        assertEquals("ignored_stale", timed.ingest(production("company", 4, 1, 20, "HIGH_LOW")));
        assertEquals(
                10L,
                ((Number)
                                timed.listStatus(null, null, null, 0, 100)
                                        .getFirst()
                                        .get("counter_value"))
                        .longValue());
    }

    @Test
    void mixedStaleAndFreshStreamsReportPartialApplication() throws Exception {
        var first = production("company", 0, 1, 100, "HIGH_LOW").datas().getFirst();
        var second = new ModbusData.Data(first.time(), 2, 3, 1, first.vals(), 1, "HIGH_LOW");
        repository.ingest(
                new ModbusData(
                        UUID.randomUUID().toString(), "company", "ND01", List.of(first, second)));
        repository.ingest(production("company", 10, 1, 110, "HIGH_LOW"));
        var old = production("company", 5, 1, 105, "HIGH_LOW").datas().getFirst();
        var fresh = new ModbusData.Data(old.time(), 2, 3, 1, old.vals(), 1, "HIGH_LOW");
        var mixed =
                new ModbusData(
                        UUID.randomUUID().toString(), "company", "ND01", List.of(old, fresh));
        assertEquals("accepted_partial", repository.ingest(mixed));
        assertEquals("duplicate", repository.ingest(mixed));
        var runs = repository.listRuns("company", "ND01", null, 0, 100);
        assertEquals(10L, ((Number) runs.get(0).get("production_count")).longValue());
        assertEquals(5L, ((Number) runs.get(1).get("production_count")).longValue());
    }

    @Test
    void mixedIngestionAndJsonApiContainOnlyExcelAndText() throws Exception {
        repository.ingest(event(0, 10, true));
        var text = event(1, 20, true);
        var excel =
                new Event(
                        text.schemaVersion(),
                        text.eventId(),
                        "company02",
                        "sheet",
                        "excel",
                        "EQ01",
                        text.observedAt(),
                        text.collectedAt(),
                        text.measurements(),
                        text.context(),
                        text.origin());
        repository.ingest(excel);
        repository.ingest(production("company", 0, 1, 100, "HIGH_LOW"));
        repository.ingest(production("company", 5, 1, 108, "HIGH_LOW"));
        assertEquals(2, repository.listJsonData(null, null, null, 0, 100).size());
        assertTrue(repository.listJsonData(null, null, "modbus_tcp", 0, 100).isEmpty());
        assertEquals(
                8L,
                ((Number)
                                repository
                                        .listRuns(null, null, null, 0, 100)
                                        .getFirst()
                                        .get("production_count"))
                        .longValue());
        try (var db = DriverManager.getConnection(url, user, password);
                var s = db.createStatement()) {
            try (var r =
                    s.executeQuery(
                            "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE"
                                    + " TABLE_SCHEMA=DATABASE() AND TABLE_NAME='modbus_state' AND"
                                    + " COLUMN_NAME IN ('payload','last_data_id')")) {
                assertTrue(r.next());
                assertEquals(0, r.getInt(1));
            }
            assertThrows(
                    java.sql.SQLException.class,
                    () -> s.executeUpdate("UPDATE data_json SET payload='[]'"));
        }
        var config = new ImsConfig();
        config.port = 0;
        try (var server = new ImsServer(config, repository, TOKEN);
                var client = HttpClient.newHttpClient()) {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            var response = get(client, base, "data-json");
            assertEquals(200, response.statusCode());
            assertEquals(2, Json.MAPPER.readTree(response.body()).path("items").size());
            assertEquals(
                    1,
                    Json.MAPPER
                            .readTree(
                                    get(
                                                    client,
                                                    base,
                                                    "data-json?com_cd=company02&collectionType=excel")
                                            .body())
                            .path("items")
                            .size());
            assertEquals(
                    400,
                    post(
                                    client,
                                    base,
                                    Json.write(text)
                                            .replace(
                                                    "\"sourceType\":\"text\"",
                                                    "\"sourceType\":\"modbus_tcp\""),
                                    text.eventId())
                            .statusCode());
        }
    }

    @Test
    void oneEquipmentIsSharedByExcelTextAndModbus() throws Exception {
        var text = event(0, 10, true);
        repository.ingest(text);
        var excel =
                new Event(
                        text.schemaVersion(),
                        UUID.randomUUID().toString(),
                        text.comCd(),
                        "sheet",
                        "excel",
                        text.equipmentId(),
                        text.observedAt(),
                        text.collectedAt(),
                        text.measurements(),
                        text.context(),
                        new Event.Origin("file.xlsx", 2L));
        repository.ingest(excel);
        var packet = production("company", 1, 1, 100, "HIGH_LOW");
        repository.ingest(new ModbusData(packet.eventId(), packet.comCd(), "EQ01", packet.datas()));
        var files = repository.listJsonData("company", "EQ01", null, 0, 100);
        var runs = repository.listRuns("company", "EQ01", null, 0, 100);
        var states = repository.listStatus("company", "EQ01", null, 0, 100);
        assertEquals(2, files.size());
        assertEquals(1, runs.size());
        assertEquals(1, states.size());
        var id = files.getFirst().get("equipment_id");
        assertEquals(id, files.get(1).get("equipment_id"));
        assertEquals(id, runs.getFirst().get("equipment_id"));
        assertEquals(id, states.getFirst().get("equipment_id"));
        assertEquals(
                Set.of("input", "sheet"),
                files.stream()
                        .map(row -> (String) row.get("source_code"))
                        .collect(java.util.stream.Collectors.toSet()));
        try (var db = DriverManager.getConnection(url, user, password);
                var s = db.createStatement()) {
            try (var r = s.executeQuery("SELECT id,com_cd,equipment_code FROM equipment")) {
                assertTrue(r.next());
                assertEquals(((Number) id).longValue(), r.getLong(1));
                assertEquals("company", r.getString(2));
                assertEquals("EQ01", r.getString(3));
                assertFalse(r.next());
            }
            assertThrows(
                    java.sql.SQLException.class,
                    () -> s.executeUpdate("DELETE FROM equipment WHERE id=" + id));
            for (String table : List.of("equipment", "data_json", "data_modbus", "modbus_state")) {
                try (var r =
                        s.executeQuery(
                                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE"
                                        + " TABLE_SCHEMA=DATABASE() AND TABLE_NAME='"
                                        + table
                                        + "' AND COLUMN_NAME='com_cd' AND IS_NULLABLE='NO'")) {
                    assertTrue(r.next());
                    assertEquals(1, r.getInt(1));
                }
            }
            try (var r =
                    s.executeQuery(
                            "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE"
                                    + " TABLE_SCHEMA=DATABASE() AND COLUMN_NAME IN"
                                    + " ('company_id','source_id')")) {
                assertTrue(r.next());
                assertEquals(0, r.getInt(1));
            }
        }
    }

    @Test
    void stateCannotReferenceAnotherEquipmentOrUnitProductionPeriod() throws Exception {
        repository.ingest(production("company", 0, 1, 10, "HIGH_LOW"));
        var packet = production("company", 1, 1, 20, "HIGH_LOW");
        repository.ingest(new ModbusData(packet.eventId(), packet.comCd(), "ND02", packet.datas()));
        var first = repository.listRuns("company", "ND01", null, 0, 100).getFirst();
        var second = repository.listRuns("company", "ND02", null, 0, 100).getFirst();
        try (var db = DriverManager.getConnection(url, user, password);
                var s = db.createStatement()) {
            assertThrows(
                    java.sql.SQLException.class,
                    () ->
                            s.executeUpdate(
                                    "UPDATE modbus_state SET current_run_id="
                                            + second.get("id")
                                            + " WHERE equipment_id="
                                            + first.get("equipment_id")));
            assertThrows(
                    java.sql.SQLException.class,
                    () ->
                            s.executeUpdate(
                                    "UPDATE modbus_state SET unit_id=2 WHERE equipment_id="
                                            + first.get("equipment_id")));
        }
    }

    @Test
    void companyCodeIsRequiredAndOldQueryNamesCannotBroadenTheResults() throws Exception {
        var config = new ImsConfig();
        config.port = 0;
        var value = event(0, 10, true);
        var body = Json.MAPPER.valueToTree(value);
        assertTrue(body.has("com_cd"));
        assertFalse(body.has("companyId"));
        try (var server = new ImsServer(config, repository, TOKEN);
                var client = HttpClient.newHttpClient()) {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            assertEquals(200, post(client, base, Json.write(value), value.eventId()).statusCode());
            assertEquals(
                    1,
                    Json.MAPPER
                            .readTree(get(client, base, "data-json?com_cd=company").body())
                            .path("items")
                            .size());
            assertEquals(
                    0,
                    Json.MAPPER
                            .readTree(get(client, base, "data-json?com_cd=missing").body())
                            .path("items")
                            .size());
            assertEquals(400, get(client, base, "data-json?companyId=company").statusCode());
            assertEquals(
                    400,
                    post(
                                    client,
                                    base,
                                    Json.write(value).replace("\"com_cd\"", "\"companyId\""),
                                    value.eventId())
                            .statusCode());
            ((com.fasterxml.jackson.databind.node.ObjectNode) body).remove("com_cd");
            assertEquals(400, post(client, base, Json.write(body), value.eventId()).statusCode());
        }
    }

    @Test
    void oldCompanySourceSchemaMustBeExplicitlyReset() throws Exception {
        try (var db = DriverManager.getConnection(url, user, password);
                var s = db.createStatement()) {
            s.execute("CREATE TABLE company(id VARCHAR(80) PRIMARY KEY)");
            s.execute("INSERT INTO company VALUES('old')");
            assertThrows(java.sql.SQLException.class, () -> new Repository(url, user, password));
            try (var r = s.executeQuery("SELECT COUNT(*) FROM company")) {
                assertTrue(r.next());
                assertEquals(1, r.getInt(1));
            }
        }
    }

    private ModbusData production(
            String company, int seconds, int status, long counter, String order) {
        return productionAt(
                company,
                java.time.Instant.parse("2026-09-22T00:00:00Z").plusSeconds(seconds).toString(),
                status,
                counter,
                order);
    }

    private ModbusData productionAt(
            String company, String time, int status, long counter, String order) {
        int high = (int) (counter >>> 16) & 65535, low = (int) counter & 65535;
        var vals =
                order.equals("HIGH_LOW") ? List.of(status, high, low) : List.of(status, low, high);
        return new ModbusData(
                UUID.randomUUID().toString(),
                company,
                "ND01",
                List.of(new ModbusData.Data(time, 1, 3, 1, vals, 1, order)));
    }

    @Test
    void oldStorageRequiresExplicitCleanupBeforeHubCreation() throws Exception {
        // 전환 과정에서 기존 테스트 데이터가 오류 없이 사라지거나 다른 데이터로 재분류되지 않는지 확인합니다.
        try (var db = DriverManager.getConnection(url, user, password);
                var s = db.createStatement()) {
            s.execute(
                    "CREATE TABLE events (seq BIGINT PRIMARY KEY, com_cd VARCHAR(80), payload"
                            + " JSON)");
            s.execute("INSERT INTO events VALUES(1,'standard','{\"vals\":[1,2,3]}')");
            var error =
                    assertThrows(
                            java.sql.SQLException.class,
                            () -> new Repository(url, user, password, true));
            assertTrue(
                    error.getMessage()
                            .contains("Remove the incompatible events/data_value tables"));
            try (var r = s.executeQuery("SELECT seq,com_cd,payload FROM events")) {
                assertTrue(r.next());
                assertEquals(1, r.getLong(1));
                assertEquals("standard", r.getString(2));
                assertEquals(3, Json.MAPPER.readTree(r.getString(3)).path("vals").size());
            }
            s.execute("DROP TABLE events");
            new Repository(url, user, password, true);
            try (var r =
                    s.executeQuery(
                            "SELECT COUNT(*) FROM information_schema.TABLES WHERE"
                                    + " TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN"
                                    + " ('events','legacy_events','legacy_data')")) {
                assertTrue(r.next());
                assertEquals(0, r.getInt(1));
            }
        }
    }

    @Test
    void startupRejectsLegacyDateColumnsWithoutChangingStoredData() throws Exception {
        repository.ingest(event(0, 100, true));
        try (var db = DriverManager.getConnection(url, user, password);
                var s = db.createStatement()) {
            s.execute("ALTER TABLE data_json MODIFY observed_at VARCHAR(40) NOT NULL");
            var error =
                    assertThrows(
                            java.sql.SQLException.class,
                            () -> new Repository(url, user, password, false));
            assertTrue(error.getMessage().contains("Convert data_json.observed_at to DATETIME"));
            try (var r = s.executeQuery("SELECT COUNT(*) FROM data_json")) {
                assertTrue(r.next());
                assertEquals(1, r.getInt(1));
            }
        }
    }

    private HttpResponse<String> get(HttpClient client, String base, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/" + path))
                        .header("Authorization", "Bearer " + TOKEN)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient client, String base, String body, String key)
            throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/events"))
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", key)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

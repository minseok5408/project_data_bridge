package io.databridge.ims;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;

class ImsServerTest {
    private static final String TOKEN = "unit-test-token-123456";
    private final MemoryRepository repository = new MemoryRepository();
    private ImsServer server;
    private HttpClient client;
    private String base;

    @BeforeEach
    void start() throws Exception {
        var config = new ImsConfig();
        config.port = 0;
        config.maxBodyBytes = 1024;
        server = new ImsServer(config, repository, TOKEN);
        server.start();
        client = HttpClient.newHttpClient();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void close() {
        if (client != null) client.close();
        if (server != null) server.close();
    }

    private HttpResponse<String> post(String body, String eventId, String token, String contentType)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create(base + "/api/v1/data"))
                        .header("Idempotency-Key", eventId)
                        .header("Content-Type", contentType);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(
                request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private ModbusData packet() {
        return ProductionCalculatorTest.packet(Instant.now().toString());
    }

    private static void assertError(
            HttpResponse<String> response, int status, String code, String field) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        var body = Json.MAPPER.readTree(response.body());
        assertEquals(code, body.path("code").asText());
        if (field != null) assertEquals(field, body.path("field").asText());
        assertFalse(body.path("message").asText().isBlank());
    }

    @Test
    void authenticationRejectsBeforeCallingStorage() throws Exception {
        var packet = packet();
        for (String token : Arrays.asList(null, "wrong-token")) {
            var response = post(Json.write(packet), packet.eventId(), token, "application/json");
            assertError(response, 401, "UNAUTHORIZED", null);
            assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElseThrow());
        }
        assertEquals(0, repository.writes);
    }

    @Test
    void healthChecksStorageWithoutBearerToken() throws Exception {
        var response =
                client.send(
                        HttpRequest.newBuilder(URI.create(base + "/health")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals(1, repository.healthChecks);
    }

    @Test
    void contentTypeSizeAndIdempotencyErrorsHaveStructuredDetails() throws Exception {
        var packet = packet();
        assertError(
                post(Json.write(packet), packet.eventId(), TOKEN, "text/plain"),
                415,
                "UNSUPPORTED_MEDIA_TYPE",
                "Content-Type");
        assertError(
                post(" ".repeat(1025), packet.eventId(), TOKEN, "application/json"),
                413,
                "REQUEST_TOO_LARGE",
                "body");
        assertError(
                post(Json.write(packet), "wrong", TOKEN, "application/json"),
                400,
                "IDEMPOTENCY_KEY_MISMATCH",
                "Idempotency-Key");
        assertEquals(0, repository.writes);
    }

    @Test
    void nestedValidationIdentifiesFieldWithoutEchoingInput() throws Exception {
        var packet = packet();
        String secret = "secret-submitted-value";
        var json = Json.MAPPER.valueToTree(packet);
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.path("datas").get(0))
                .put("counterWordOrder", secret);
        var response = post(Json.write(json), packet.eventId(), TOKEN, "application/json");
        assertError(response, 400, "INVALID_REQUEST", "datas[0].counterWordOrder");
        assertFalse(response.body().contains(secret));
        assertEquals(0, repository.writes);
    }

    @Test
    void missingCompanyAndInvalidTimestampHaveActionableFields() throws Exception {
        var packet = packet();
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(packet);
        json.remove("com_cd");
        assertError(
                post(Json.write(json), packet.eventId(), TOKEN, "application/json"),
                400,
                "INVALID_REQUEST",
                "com_cd");
        json = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(packet);
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.path("datas").get(0))
                .put("time", "private-invalid-date");
        var response = post(Json.write(json), packet.eventId(), TOKEN, "application/json");
        assertError(response, 400, "INVALID_REQUEST", "datas[0].time");
        assertFalse(response.body().contains("private-invalid-date"));
    }

    @Test
    void fractionalRegisterAndMalformedJsonDoNotExposeValues() throws Exception {
        var packet = packet();
        var json = Json.MAPPER.valueToTree(packet);
        ((com.fasterxml.jackson.databind.node.ArrayNode) json.path("datas").get(0).path("vals"))
                .set(1, Json.MAPPER.valueToTree(123.5));
        var response = post(Json.write(json), packet.eventId(), TOKEN, "application/json");
        assertError(response, 400, "INVALID_REQUEST", "datas[0].vals[1]");
        assertFalse(response.body().contains("123.5"));
        response = post("{\"secret\":private-value", packet.eventId(), TOKEN, "application/json");
        assertError(response, 400, "INVALID_REQUEST", "body");
        assertFalse(response.body().contains("private-value"));
    }

    @Test
    void invalidPaginationIsRejectedBeforeStorage() throws Exception {
        for (String query : List.of("after=-1", "limit=1001", "limit=abc")) {
            var response =
                    client.send(
                            HttpRequest.newBuilder(URI.create(base + "/api/v1/data-json?" + query))
                                    .header("Authorization", "Bearer " + TOKEN)
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertError(
                    response,
                    400,
                    "INVALID_REQUEST",
                    query.startsWith("after") ? "after" : "limit");
        }
        assertEquals(0, repository.reads);
    }

    @Test
    void storageValidationConflictAndOutcomesPreserveTheirContract() throws Exception {
        var packet = packet();
        repository.failure =
                new ValidationException(
                        "FUTURE_OBSERVATION", "datas[0].time", "Check the collector clock");
        assertError(
                post(Json.write(packet), packet.eventId(), TOKEN, "application/json"),
                400,
                "FUTURE_OBSERVATION",
                "datas[0].time");
        repository.failure = new Repository.Conflict("private-storage-details");
        var response = post(Json.write(packet), packet.eventId(), TOKEN, "application/json");
        assertError(response, 409, "EVENT_CONFLICT", "eventId");
        assertFalse(response.body().contains("private-storage-details"));
        repository.failure = null;
        for (String outcome :
                List.of("accepted", "duplicate", "ignored_stale", "accepted_partial")) {
            repository.outcome = outcome;
            response = post(Json.write(packet), packet.eventId(), TOKEN, "application/json");
            assertEquals(200, response.statusCode());
            assertEquals(
                    new Event.Ack(packet.eventId(), outcome),
                    Json.read(response.body(), Event.Ack.class));
        }
    }

    @Test
    void fileBatchIsValidatedCompletelyBeforeOneStorageCall() throws Exception {
        var batch = FileBatchTest.batch(FileBatchTest.event(1, 10), FileBatchTest.event(2, 20));
        var json = Json.MAPPER.valueToTree(batch);
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.path("events").get(1))
                .put("com_cd", "other");
        assertError(
                post(Json.write(json), batch.eventId(), TOKEN, "application/json"),
                400,
                "INVALID_REQUEST",
                "events[1].com_cd");
        assertEquals(0, repository.writes);
        json = Json.MAPPER.valueToTree(batch);
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.path("events").get(1).path("origin"))
                .put("row", 2.5);
        assertError(
                post(Json.write(json), batch.eventId(), TOKEN, "application/json"),
                400,
                "INVALID_REQUEST",
                "events[1].origin.row");
        assertEquals(0, repository.writes);
        var response = post(Json.write(batch), batch.eventId(), TOKEN, "application/json");
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(
                new Event.Ack(batch.eventId(), "accepted"),
                Json.read(response.body(), Event.Ack.class));
        assertEquals(1, repository.writes);
    }

    private static final class MemoryRepository implements HubRepository {
        int writes, reads, healthChecks;
        String outcome = "accepted";
        RuntimeException failure;

        public void health() {
            healthChecks++;
        }

        public String ingest(Event event) {
            return write();
        }

        public String ingest(FileBatch batch) {
            return write();
        }

        public String ingest(ModbusData data) {
            return write();
        }

        private String write() {
            if (failure != null) throw failure;
            writes++;
            return outcome;
        }

        public List<Map<String, Object>> listJsonData(
                String company,
                String equipment,
                String source,
                String type,
                long after,
                int limit) {
            reads++;
            return List.of();
        }

        public List<Map<String, Object>> listRuns(
                String company, String equipment, String source, long after, int limit) {
            reads++;
            return List.of();
        }

        public List<Map<String, Object>> listStatus(
                String company, String equipment, String source, long after, int limit) {
            reads++;
            return List.of();
        }
    }
}

package io.databridge.ims;

import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

public final class ImsServer implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(ImsServer.class.getName());
    private final ImsConfig config;
    private final HubRepository repository;
    private final byte[] credential;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;

    public ImsServer(ImsConfig config, HubRepository repository, String token) throws IOException {
        this.config = config;
        this.repository = repository;
        credential = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        permits = new Semaphore(config.maxConcurrentRequests);
        server = HttpServer.create(new InetSocketAddress(config.host, config.port), 64);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        boolean acquired = permits.tryAcquire();
        try (exchange) {
            if (!acquired) {
                error(exchange, 503, "SERVER_BUSY", null, "Server busy");
                return;
            }
            try {
                String path = exchange.getRequestURI().getPath(),
                        method = exchange.getRequestMethod();
                if (path.equals("/health") && method.equals("GET")) {
                    repository.health();
                    respond(exchange, 200, Map.of("status", "ok", "schemaVersion", "1.0"));
                    return;
                }
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                if (authorization == null
                        || !MessageDigest.isEqual(
                                credential, authorization.getBytes(StandardCharsets.UTF_8))) {
                    exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                    error(exchange, 401, "UNAUTHORIZED", null, "Invalid bearer token");
                    return;
                }
                if ((path.equals("/api/v1/data") || path.equals("/api/v1/events"))
                        && method.equals("POST")) {
                    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                    if (contentType == null
                            || !contentType
                                    .toLowerCase(Locale.ROOT)
                                    .split(";", 2)[0]
                                    .trim()
                                    .equals("application/json")) {
                        error(
                                exchange,
                                415,
                                "UNSUPPORTED_MEDIA_TYPE",
                                "Content-Type",
                                "application/json required");
                        return;
                    }
                    byte[] body = exchange.getRequestBody().readNBytes(config.maxBodyBytes + 1);
                    if (body.length > config.maxBodyBytes) {
                        error(exchange, 413, "REQUEST_TOO_LARGE", "body", "Request too large");
                        return;
                    }
                    var json = Json.MAPPER.readTree(body);
                    if (json == null || !json.isObject())
                        throw new ValidationException("body", "Event object required");
                    String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                    if (!json.path("eventId").isTextual()
                            || !json.path("eventId").asText().equals(key)) {
                        error(
                                exchange,
                                400,
                                "IDEMPOTENCY_KEY_MISMATCH",
                                "Idempotency-Key",
                                "Idempotency-Key must match eventId");
                        return;
                    }
                    String eventId, status;
                    if (json.has("events")) {
                        FileBatch batch =
                                Json.MAPPER
                                        .readerFor(FileBatch.class)
                                        .without(
                                                com.fasterxml.jackson.databind
                                                        .DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                                        .readValue(json);
                        eventId = batch.eventId();
                        status = repository.ingest(batch);
                    } else if (json.has("datas") || json.has("nodeId")) {
                        ModbusData data =
                                Json.MAPPER
                                        .readerFor(ModbusData.class)
                                        .without(
                                                com.fasterxml.jackson.databind
                                                        .DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                                        .readValue(json);
                        eventId = data.eventId();
                        status = repository.ingest(data);
                    } else {
                        var event = Json.MAPPER.treeToValue(json, Event.class);
                        eventId = event.eventId();
                        status = repository.ingest(event);
                    }
                    respond(exchange, 200, new Event.Ack(eventId, status));
                    return;
                }
                if ((path.equals("/api/v1/data-json")
                                || path.equals("/api/v1/data")
                                || path.equals("/api/v1/events"))
                        && method.equals("GET")) {
                    var query = query(exchange.getRequestURI().getRawQuery());
                    long after = number(query, "after", 0, 0, Long.MAX_VALUE);
                    int limit = (int) number(query, "limit", 100, 1, 1000);
                    respond(
                            exchange,
                            200,
                            Map.of(
                                    "items",
                                    repository.listJsonData(
                                            query.get("com_cd"),
                                            query.get("equipmentId"),
                                            query.get("sourceId"),
                                            query.get("collectionType"),
                                            after,
                                            limit)));
                    return;
                }
                if ((path.equals("/api/v1/modbus/runs") || path.equals("/api/v1/modbus/status"))
                        && method.equals("GET")) {
                    var query = query(exchange.getRequestURI().getRawQuery());
                    long after = number(query, "after", 0, 0, Long.MAX_VALUE);
                    int limit = (int) number(query, "limit", 100, 1, 1000);
                    var items =
                            path.endsWith("/runs")
                                    ? repository.listRuns(
                                            query.get("com_cd"),
                                            query.get("equipmentId"),
                                            query.get("sourceId"),
                                            after,
                                            limit)
                                    : repository.listStatus(
                                            query.get("com_cd"),
                                            query.get("equipmentId"),
                                            query.get("sourceId"),
                                            after,
                                            limit);
                    respond(exchange, 200, Map.of("items", items));
                    return;
                }

                error(exchange, 404, "NOT_FOUND", null, "Route not found");
            } catch (Repository.Conflict e) {
                error(
                        exchange,
                        409,
                        "EVENT_CONFLICT",
                        "eventId",
                        "eventId already exists with different content for this company");
            } catch (IllegalArgumentException
                    | com.fasterxml.jackson.core.JsonProcessingException e) {
                validationError(exchange, e);
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Request failed", e);
                error(exchange, 500, "INTERNAL_ERROR", null, "Storage or server failure");
            }
        } finally {
            if (acquired) permits.release();
        }
    }

    private static Map<String, String> query(String raw) {
        var result = new HashMap<String, String>();
        try {
            if (raw != null && !raw.isEmpty())
                for (String part : raw.split("&")) {
                    var pair = part.split("=", 2);
                    result.put(
                            URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                            pair.length == 2
                                    ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
                                    : "");
                }
        } catch (IllegalArgumentException e) {
            throw new ValidationException("query", "Malformed query encoding");
        }
        if (!Set.of("com_cd", "equipmentId", "sourceId", "collectionType", "after", "limit")
                .containsAll(result.keySet()))
            throw new ValidationException("query", "Unsupported query parameter");
        return result;
    }

    private static long number(
            Map<String, String> query, String field, long fallback, long min, long max) {
        try {
            long value = Long.parseLong(query.getOrDefault(field, Long.toString(fallback)));
            if (value < min || value > max) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new ValidationException(
                    field, "Expected an integer between " + min + " and " + max);
        }
    }

    private static void validationError(HttpExchange exchange, Exception exception)
            throws IOException {
        String path = "";
        if (exception instanceof com.fasterxml.jackson.databind.JsonMappingException mapping) {
            var names = new StringBuilder();
            for (var reference : mapping.getPath()) {
                String name = reference.getFieldName();
                if (name != null) {
                    // JSON 경로의 맵 키도 외부 입력이므로 임의의 긴 문자열은 응답에 노출하지 않습니다.
                    if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,79}")) break;
                    if (!names.isEmpty()) names.append('.');
                    names.append(name);
                } else if (reference.getIndex() >= 0)
                    names.append('[').append(reference.getIndex()).append(']');
                if (names.length() > 160) {
                    names.setLength(0);
                    break;
                }
            }
            path = names.toString();
        }
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ValidationException validation) {
                String field = validation.field();
                if (!path.isEmpty() && !field.startsWith(path)) field = path + "." + field;
                error(exchange, 400, validation.code(), field, validation.getMessage());
                return;
            }
        }
        error(
                exchange,
                400,
                "INVALID_REQUEST",
                path.isEmpty() ? "body" : path,
                "Malformed JSON or incompatible field value");
    }

    private static void error(
            HttpExchange exchange, int status, String code, String field, String message)
            throws IOException {
        var body = new LinkedHashMap<String, String>();
        body.put("code", code);
        if (field != null) body.put("field", field);
        body.put("message", message);
        respond(exchange, status, body);
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(2);
        executor.shutdownNow();
    }
}

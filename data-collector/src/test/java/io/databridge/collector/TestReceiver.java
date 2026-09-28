package io.databridge.collector;

import com.sun.net.httpserver.HttpServer;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

final class TestReceiver implements AutoCloseable {
    final List<Event> events = new CopyOnWriteArrayList<>();
    final List<FileBatch> batches = new CopyOnWriteArrayList<>();
    final List<String> batchBodies = new CopyOnWriteArrayList<>();
    final Map<String, String> receipts = new ConcurrentHashMap<>();
    final List<ModbusData> modbus = new CopyOnWriteArrayList<>();
    final AtomicInteger requests = new AtomicInteger();
    volatile int failAt;
    volatile int responseStatus = 200;
    volatile String acknowledgement;
    volatile String acknowledgementStatus = "accepted";
    volatile String authorization;
    volatile Runnable onRequest;
    private final HttpServer server;

    TestReceiver() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    try (exchange) {
                        var json = Json.MAPPER.readTree(exchange.getRequestBody());
                        authorization = exchange.getRequestHeaders().getFirst("Authorization");
                        int status = requests.incrementAndGet() == failAt ? 503 : responseStatus;
                        String ackStatus = acknowledgementStatus;
                        FileBatch batch = null;
                        if (json.has("events")) {
                            batch = Json.MAPPER.treeToValue(json, FileBatch.class);
                            batches.add(batch);
                            batchBodies.add(Json.write(batch));
                        }
                        if (status == 200) {
                            if (batch != null) {
                                String hash =
                                        Json.hash(
                                                Json.write(batch).getBytes(StandardCharsets.UTF_8));
                                String previous = receipts.putIfAbsent(batch.eventId(), hash);
                                if (previous == null) events.addAll(batch.events());
                                else if (previous.equals(hash)) ackStatus = "duplicate";
                                else status = 409;
                            } else if (json.has("datas"))
                                modbus.add(Json.MAPPER.treeToValue(json, ModbusData.class));
                            else events.add(Json.MAPPER.treeToValue(json, Event.class));
                        }
                        var callback = onRequest;
                        if (callback != null) callback.run();
                        byte[] body =
                                (acknowledgement != null
                                                ? acknowledgement
                                                : Json.write(
                                                        new Event.Ack(
                                                                json.path("eventId").asText(),
                                                                ackStatus)))
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(status, body.length);
                        exchange.getResponseBody().write(body);
                    }
                });
        server.start();
    }

    String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/events";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

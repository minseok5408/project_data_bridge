package io.databridge.collector;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

import java.util.*;

class DeliveryTest {
    Event event() {
        return new Event(
                "1.0",
                UUID.randomUUID().toString(),
                "standard",
                "input",
                "text",
                "EQ1",
                "2026-09-22T09:00:00+09:00",
                "2026-09-22T09:00:00+09:00",
                Map.of("temperature", new Event.Measurement(1, "sensor", null)),
                Map.of(),
                null);
    }

    @Test
    void sendsImmediatelyWithAuthenticationAndMatchingAck() throws Exception {
        try (var receiver = new TestReceiver()) {
            var config = new CollectorConfig();
            config.endpoint = receiver.endpoint();
            var event = event();
            try (var sender = new Sender(config, "test-token-123456789")) {
                sender.send(event);
            }
            assertEquals(1, receiver.events.size());
            assertEquals(event.eventId(), receiver.events.getFirst().eventId());
            assertEquals("Bearer test-token-123456789", receiver.authorization);
        }
    }

    @Test
    void badAcknowledgementIsReportedToCaller() throws Exception {
        try (var receiver = new TestReceiver()) {
            receiver.acknowledgement = "{}";
            var config = new CollectorConfig();
            config.endpoint = receiver.endpoint();
            try (var sender = new Sender(config, "test-token-123456789")) {
                assertThrows(IllegalStateException.class, () -> sender.send(event()));
            }
            assertEquals(1, receiver.requests.get());
        }
    }

    @Test
    void connectionFailureNamesTheStageAndDestinationEvenWithNoExceptionMessage() throws Exception {
        var config = new CollectorConfig();
        config.timeoutSeconds = 1;
        try (var unusedPort =
                new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            config.endpoint = "http://127.0.0.1:" + unusedPort.getLocalPort() + "/test-receiver";
        }
        try (var sender = new Sender(config, "test-token-123456789")) {
            var error = assertThrows(java.io.IOException.class, () -> sender.send(event()));
            assertTrue(error.getMessage().contains("IMS_SEND_FAILED"));
            assertTrue(error.getMessage().contains(config.endpoint));
            assertInstanceOf(java.net.ConnectException.class, error.getCause());
            assertTrue(error.getMessage().contains("ConnectException"));
            assertFalse(error.getMessage().contains("test-token-123456789"));
        }
    }

    @Test
    void missingTokenIsRejectedBeforeCreatingTheHttpClient() {
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new Sender(new CollectorConfig(), (String) null));
        assertTrue(error.getMessage().contains("ims.token"));
    }

    @Test
    void failedRequestIsNotQueuedOrRetriedBySender() throws Exception {
        try (var receiver = new TestReceiver()) {
            receiver.responseStatus = 503;
            var config = new CollectorConfig();
            config.endpoint = receiver.endpoint();
            try (var sender = new Sender(config, "test-token-123456789")) {
                assertThrows(IllegalStateException.class, () -> sender.send(event()));
            }
            assertEquals(1, receiver.requests.get());
            assertTrue(receiver.events.isEmpty());
        }
    }

    @Test
    void structuredHubErrorIsBoundedSanitizedAndDoesNotLogPayloadOrToken() throws Exception {
        String token = "delivery-private-test-token";
        try (var receiver = new TestReceiver()) {
            receiver.responseStatus = 400;
            receiver.acknowledgement =
                    Json.write(
                            Map.of(
                                    "code",
                                    "INVALID_REQUEST",
                                    "field",
                                    "datas.time",
                                    "message",
                                    "bad\r\n\t\u001b[31m " + token + " " + "x".repeat(600),
                                    "internal",
                                    "must-not-be-logged"));
            var config = new CollectorConfig();
            config.endpoint = receiver.endpoint();
            try (var sender = new Sender(config, token)) {
                var error = assertThrows(IllegalStateException.class, () -> sender.send(event()));
                String message = error.getMessage();
                assertTrue(message.contains("HTTP상태=400"));
                assertTrue(message.contains("code=INVALID_REQUEST"));
                assertTrue(message.contains("field=datas.time"));
                assertTrue(message.contains("[REDACTED]"));
                assertFalse(message.contains(token));
                assertFalse(message.contains("must-not-be-logged"));
                assertFalse(message.contains("measurements"));
                assertFalse(message.contains("\r"));
                assertFalse(message.contains("\n"));
                assertFalse(message.contains("\u001b"));
                assertTrue(message.length() < 1000);
            }
        }
    }

    @Test
    void oversizedHubResponseIsStoppedAndOmittedFromDiagnostics() throws Exception {
        try (var receiver = new TestReceiver()) {
            receiver.responseStatus = 503;
            receiver.acknowledgement = "sensitive-server-body".repeat(1000);
            var config = new CollectorConfig();
            config.endpoint = receiver.endpoint();
            try (var sender = new Sender(config, "delivery-test-token-only")) {
                var error = assertThrows(IllegalStateException.class, () -> sender.send(event()));
                assertTrue(error.getMessage().contains("HTTP상태=503"));
                assertTrue(error.getMessage().contains("응답 크기 제한 초과"));
                assertFalse(error.getMessage().contains("sensitive-server-body"));
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"accepted_partial", "ignored_stale"})
    void modbusOnlyAcknowledgementsCannotCompleteAFileBatch(String status) throws Exception {
        try (var receiver = new TestReceiver()) {
            receiver.acknowledgementStatus = status;
            var config = new CollectorConfig();
            config.endpoint = receiver.endpoint();
            try (var sender = new Sender(config, "delivery-test-token-only")) {
                assertThrows(IllegalStateException.class, () -> sender.send(batch()));
            }
        }
    }

    @Test
    void fileBatchLogContainsSummaryWithoutMeasurementValues() throws Exception {
        var messages = new ArrayList<String>();
        var logger = java.util.logging.Logger.getLogger(Sender.class.getName());
        var handler =
                new java.util.logging.Handler() {
                    @Override
                    public void publish(java.util.logging.LogRecord record) {
                        messages.add(record.getMessage());
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {}
                };
        logger.addHandler(handler);
        try (var receiver = new TestReceiver()) {
            var config = new CollectorConfig();
            config.endpoint = receiver.endpoint();
            var batch = batch();
            try (var sender = new Sender(config, "delivery-test-token-only")) {
                sender.send(batch);
            }
            String log = String.join("\n", messages);
            assertTrue(log.contains(batch.eventId()));
            assertTrue(log.contains("파일명=values.txt"));
            assertTrue(log.contains("행수=1"));
            assertFalse(log.contains("measurements"));
            assertFalse(log.contains("temperature"));
            assertTrue(log.length() < 500);
        } finally {
            logger.removeHandler(handler);
        }
    }

    private FileBatch batch() {
        Event value = event();
        Event row =
                new Event(
                        value.schemaVersion(),
                        value.eventId(),
                        value.comCd(),
                        value.sourceId(),
                        value.sourceType(),
                        value.equipmentId(),
                        value.observedAt(),
                        value.collectedAt(),
                        value.measurements(),
                        value.context(),
                        new Event.Origin("values.txt", 1L));
        return new FileBatch(UUID.randomUUID().toString(), List.of(row));
    }
}

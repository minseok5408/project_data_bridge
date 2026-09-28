package io.databridge.collector;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

class ApplicationPropertiesTest {
    @TempDir Path temp;

    @Test
    void classpathTokenAuthenticatesRequestsWithoutAnEnvironmentVariable() throws Exception {
        assertEquals("collector-test-fixture-token-only", ApplicationProperties.loadToken());
        try (var receiver = new TestReceiver()) {
            var config = new CollectorConfig();
            config.endpoint = receiver.endpoint();
            try (var sender = new Sender(config)) {
                sender.send(new DeliveryTest().event());
            }
            assertEquals("Bearer " + ApplicationProperties.loadToken(), receiver.authorization);
            assertEquals(1, receiver.events.size());
        }
    }

    @Test
    void externalFileBesideSelectedCommonConfigOverridesBundledToken() throws Exception {
        Files.writeString(
                temp.resolve("application.properties"),
                "# 외부 배포 설정\nims.token=external-test-token-1234\n");
        assertEquals(
                "external-test-token-1234",
                ApplicationProperties.loadToken(temp.resolve("collector.json")));
    }

    @Test
    void missingExternalFileUsesClasspathResource() throws Exception {
        assertEquals(
                ApplicationProperties.loadToken(),
                ApplicationProperties.loadToken(temp.resolve("collector.json")));
    }

    @Test
    void invalidExternalTokenFailsWithoutFallingBackOrPrintingItsValue() throws Exception {
        Files.writeString(temp.resolve("application.properties"), "ims.token=short-secret\n");
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ApplicationProperties.loadToken(temp.resolve("collector.json")));
        assertTrue(error.getMessage().contains("ims.token"));
        assertFalse(error.getMessage().contains("short-secret"));
    }

    @Test
    void misspelledPropertyAndHeaderControlCharactersAreRejected() throws Exception {
        Files.writeString(
                temp.resolve("application.properties"), "ims.tokne=external-test-token-1234\n");
        assertThrows(
                IllegalArgumentException.class,
                () -> ApplicationProperties.loadToken(temp.resolve("collector.json")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Sender(new CollectorConfig(), "token-1234567890123\r\n"));
    }
}

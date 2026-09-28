package io.databridge.ims;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

class ImsConfigTest {
    @Test
    void fileBatchBodyLimitDefaultsToSixteenMiBAndCanOnlyBeReduced() throws Exception {
        var config = ImsConfig.load(write(valid()));
        assertEquals(16 * 1024 * 1024, config.maxBodyBytes);
        config.maxBodyBytes = 1024;
        assertDoesNotThrow(config::validate);
        config.maxBodyBytes = 16 * 1024 * 1024 + 1;
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @TempDir Path temp;

    String valid() {
        return """
        # UTF-8 설정
        ims.token=properties-test-token-1234
        database.url=jdbc:mariadb://127.0.0.1:3307/properties_test
        database.username=테스트계정
        database.password=비밀번호;#=123
        server.port=8123
        modbus.max-gap-seconds=30
        database.initialize-schema=false
        """;
    }

    Path write(String content) throws Exception {
        return Files.writeString(temp.resolve("application.properties"), content);
    }

    @Test
    void readsConnectionAndAuthenticationSettingsWithoutEnvironmentVariables() throws Exception {
        var config = ImsConfig.load(write(valid()));
        assertEquals("테스트계정", config.databaseUser);
        assertEquals("비밀번호;#=123", config.databasePassword);
        assertEquals("properties-test-token-1234", config.token);
        assertEquals("jdbc:mariadb://127.0.0.1:3307/properties_test", config.databaseUrl);
        assertEquals(30, config.modbusMaxGapSeconds);
        assertEquals(8123, config.port);
        assertFalse(config.initializeSchema);
    }

    @Test
    void missingCredentialAndShortTokenHaveActionableErrorsWithoutSecrets() throws Exception {
        var userError =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                ImsConfig.load(
                                        write(
                                                valid().replace(
                                                                "database.username=테스트계정",
                                                                "database.username="))));
        assertTrue(userError.getMessage().contains("database.username"));
        var tokenError =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                ImsConfig.load(
                                        write(
                                                valid().replace(
                                                                "properties-test-token-1234",
                                                                "secret"))));
        assertTrue(tokenError.getMessage().contains("ims.token"));
        assertFalse(tokenError.getMessage().contains("secret"));
    }

    @Test
    void mistypedKeysAndInvalidValuesFailBeforeOpeningTheDatabase() throws Exception {
        for (String content :
                new String[] {
                    valid() + "database.usernmae=other\n",
                    valid().replace("8123", "not-a-port"),
                    valid().replace("initialize-schema=false", "initialize-schema=no"),
                    valid().replace("jdbc:mariadb://", "jdbc:sqlite:"),
                    valid() + "server.max-body-bytes=10\n",
                    valid().replace("max-gap-seconds=30", "max-gap-seconds=0")
                })
            assertThrows(IllegalArgumentException.class, () -> ImsConfig.load(write(content)));
    }

    @Test
    void explicitMissingFileDoesNotFallBackToClasspathSettings() {
        assertThrows(
                java.io.IOException.class,
                () -> ImsConfig.load(temp.resolve("missing.properties")));
    }
}

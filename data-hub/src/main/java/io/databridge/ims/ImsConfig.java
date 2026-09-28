package io.databridge.ims;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class ImsConfig {
    public boolean initializeSchema = true;
    public String host = "127.0.0.1";
    public int port = 8000;
    public String token = "";
    public String databaseUrl = "jdbc:mariadb://127.0.0.1:3306/ip_multimedia_subsystem";
    public String databaseUser = "";
    public String databasePassword = "";
    public int maxBodyBytes = FileBatch.MAX_BYTES;
    public int maxConcurrentRequests = 64;
    public int modbusMaxGapSeconds = 15;

    /** Maven/IntelliJ는 src/main/resources/application.properties를 클래스패스에 복사합니다. */
    public static ImsConfig load() throws IOException {
        try (var stream = ImsConfig.class.getResourceAsStream("/application.properties")) {
            if (stream == null)
                throw new FileNotFoundException(
                        "application.properties is missing from the classpath");
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return read(reader);
            }
        }
    }

    /** 외부 설정 파일을 지정하면 내장 설정 대신 사용하며, 환경변수로 덮어쓰지 않습니다. */
    public static ImsConfig load(Path file) throws IOException {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read(reader);
        }
    }

    private static ImsConfig read(Reader reader) throws IOException {
        var properties = new Properties();
        properties.load(reader);
        var config = new ImsConfig();
        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key);
            switch (key) {
                case "server.host" -> config.host = value.trim();
                case "server.port" -> config.port = integer(key, value);
                case "ims.token" -> config.token = value;
                case "database.url" -> config.databaseUrl = value.trim();
                case "database.username" -> config.databaseUser = value.trim();
                case "database.password" -> config.databasePassword = value;
                case "database.initialize-schema" -> {
                    if (!Set.of("true", "false").contains(value.trim()))
                        throw new IllegalArgumentException(key + " must be true or false");
                    config.initializeSchema = Boolean.parseBoolean(value.trim());
                }
                case "modbus.max-gap-seconds" -> config.modbusMaxGapSeconds = integer(key, value);
                case "server.max-body-bytes" -> config.maxBodyBytes = integer(key, value);
                case "server.max-concurrent-requests" ->
                        config.maxConcurrentRequests = integer(key, value);
                default ->
                        throw new IllegalArgumentException(
                                "Unknown application.properties key: " + key);
            }
        }
        config.validate();
        return config;
    }

    private static int integer(String key, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    public void validate() {
        if (modbusMaxGapSeconds < 1 || modbusMaxGapSeconds > 86400)
            throw new IllegalArgumentException("modbus.max-gap-seconds must be 1..86400");
        if (host == null
                || host.isBlank()
                || port < 0
                || port > 65535
                || maxBodyBytes < 1024
                || maxBodyBytes > FileBatch.MAX_BYTES
                || maxConcurrentRequests < 1
                || maxConcurrentRequests > 1000)
            throw new IllegalArgumentException("Invalid IMS limits/address");
        if (databaseUrl == null || !databaseUrl.startsWith("jdbc:mariadb://"))
            throw new IllegalArgumentException("database.url must use jdbc:mariadb://");
        if (databaseUser == null || databaseUser.isBlank())
            throw new IllegalArgumentException("Set database.username in application.properties");
        if (token == null
                || token.length() < 16
                || !token.chars().allMatch(c -> c >= 33 && c <= 126))
            throw new IllegalArgumentException(
                    "Set ims.token in application.properties to at least 16 printable ASCII"
                            + " characters without spaces");
    }
}

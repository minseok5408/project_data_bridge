package io.databridge.collector;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

/** 인증 설정은 수집 대상 장비의 JSON 설정과 별도로 관리합니다. */
public final class ApplicationProperties {
    private ApplicationProperties() {}

    public static String loadToken() throws IOException {
        try (var stream =
                ApplicationProperties.class.getResourceAsStream("/application.properties")) {
            if (stream == null)
                throw new FileNotFoundException(
                        "application.properties is missing from the classpath");
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return readToken(reader);
            }
        }
    }

    /** 배포 EXE는 다시 빌드하지 않고 collector.json 옆의 application.properties를 사용할 수 있습니다. */
    public static String loadToken(Path collectorFile) throws IOException {
        Path file = collectorFile.toAbsolutePath().resolveSibling("application.properties");
        if (Files.notExists(file)) return loadToken();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return readToken(reader);
        }
    }

    private static String readToken(Reader reader) throws IOException {
        var properties = new Properties();
        properties.load(reader);
        for (String key : properties.stringPropertyNames()) {
            if (!key.equals("ims.token"))
                throw new IllegalArgumentException("Unknown application.properties key: " + key);
        }
        String token = properties.getProperty("ims.token");
        requireToken(token);
        return token;
    }

    static void requireToken(String token) {
        if (token == null
                || token.length() < 16
                || !token.chars().allMatch(c -> c >= 33 && c <= 126))
            throw new IllegalArgumentException(
                    "Set ims.token in application.properties to at least 16 printable ASCII"
                            + " characters without spaces");
    }
}

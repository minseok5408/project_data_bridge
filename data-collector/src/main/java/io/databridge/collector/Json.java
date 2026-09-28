package io.databridge.collector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class Json {
    public static final ObjectMapper MAPPER =
            JsonMapper.builder()
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .build();

    private Json() {}

    public static <T> T read(Path path, Class<T> type) throws IOException {
        return MAPPER.readValue(Files.readString(path), type);
    }

    public static <T> T read(String value, Class<T> type) throws JsonProcessingException {
        return MAPPER.readValue(value, type);
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON", e);
        }
    }

    public static String hash(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

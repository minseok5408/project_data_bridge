package io.databridge.collector;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

/** 이전 버전의 행별 체크포인트를 확인하는 읽기 전용 호환 검사입니다. 새 처리 기록은 쓰지 않습니다. */
public final class Progress {
    private final Path path;
    private final int legacyRowCheckpoints;

    public Progress(Path path) throws IOException {
        this.path = path.toAbsolutePath().normalize();
        if (!Files.exists(this.path)) {
            legacyRowCheckpoints = 0;
            return;
        }
        var value = Json.MAPPER.readTree(Files.readString(this.path));
        if (value == null || !value.isObject())
            throw new IOException("Invalid legacy progress file: " + this.path);
        legacyRowCheckpoints = value.size();
    }

    public void requireAtomicFileMode() throws IOException {
        if (legacyRowCheckpoints != 0)
            throw new IOException(
                    "LEGACY_FILE_PROGRESS [이전 버전의 행별 저장 기록] 경로="
                            + path
                            + "; 이미 저장된 행을 확인하고 기록을 별도로 보관한 뒤 파일 전체를 재처리하세요. 자동 이어읽기는 지원하지 않습니다");
    }

    public Map<String, Object> status() {
        return Map.of("atomicFileDelivery", true, "legacyRowCheckpoints", legacyRowCheckpoints);
    }
}

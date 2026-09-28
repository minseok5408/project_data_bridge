package io.databridge.collector;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** 원본 파일의 이동 의도와 세대만 기록합니다. 측정값이나 전송 대기 이벤트는 저장하지 않습니다. */
final class FileLifecycle {
    static final String RETRY_SUFFIX = ".retry.json";

    private enum Phase {
        CLAIMING,
        PROCESSING,
        COMPLETE,
        ERROR
    }

    private record Metadata(
            int version,
            String generation,
            String inputName,
            String fileName,
            boolean retry,
            Phase phase,
            String archiveName,
            String collectedAt,
            String batchHash) {
        Metadata phase(Phase next, String archive) {
            return new Metadata(
                    version,
                    generation,
                    inputName,
                    fileName,
                    retry,
                    next,
                    archive,
                    collectedAt,
                    batchHash);
        }

        Metadata withHash(String hash) {
            return new Metadata(
                    version,
                    generation,
                    inputName,
                    fileName,
                    retry,
                    phase,
                    archiveName,
                    collectedAt,
                    hash);
        }
    }

    private record Retry(
            int version,
            String comCd,
            String sourceId,
            String generation,
            String fileName,
            String collectedAt,
            String batchHash) {}

    record Claimed(
            Path directory,
            String generation,
            String fileName,
            String collectedAt,
            String batchHash) {
        Path file() {
            return directory.resolve("payload").resolve(fileName);
        }
    }

    private final Path inputDirectory;
    private final Path processingRoot;
    private final CollectorConfig.Source source;
    private final Progress progress;

    FileLifecycle(Path inputDirectory, CollectorConfig.Source source, Progress progress) {
        this.inputDirectory = inputDirectory.toAbsolutePath().normalize();
        this.processingRoot =
                this.inputDirectory.resolve(".databridge").resolve(source.comCd).resolve(source.id);
        this.source = source;
        this.progress = progress;
    }

    List<Claimed> pending() throws Exception {
        progress.requireAtomicFileMode();
        checkContained(processingRoot);
        if (!Files.exists(processingRoot)) return new ArrayList<>();
        var result = new ArrayList<Claimed>();
        try (var entries = Files.list(processingRoot)) {
            for (var directory : entries.sorted().toList()) {
                checkContained(directory);
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
                    throw invalid(directory, "처리 폴더가 아닙니다");
                requireUuid(directory.getFileName().toString(), directory);
                Path manifest = directory.resolve("claim.json");
                if (!Files.exists(manifest)) {
                    discardUninstalledClaim(directory);
                    continue;
                }
                Metadata metadata = readMetadata(directory);
                cleanMetadataTemporaries(directory);
                if (metadata.phase() == Phase.CLAIMING) metadata = finishClaim(directory, metadata);
                if (metadata == null) continue;
                if (metadata.phase() == Phase.COMPLETE || metadata.phase() == Phase.ERROR) {
                    finishArchive(directory, metadata);
                } else {
                    var claimed = claimed(directory, metadata);
                    requireRegular(claimed.file());
                    result.add(claimed);
                }
            }
        }
        return result;
    }

    Claimed claim(Path input) throws Exception {
        progress.requireAtomicFileMode();
        requireRegular(input);
        String inputName = input.getFileName().toString();
        Path sidecar = safeChild(inputDirectory, inputName + RETRY_SUFFIX);
        Retry retry = Files.exists(sidecar) ? readRetry(sidecar) : null;
        String generation = retry == null ? UUID.randomUUID().toString() : retry.generation();
        String fileName = retry == null ? inputName : retry.fileName();
        checkContained(processingRoot);
        Files.createDirectories(processingRoot);
        Path directory = safeChild(processingRoot, generation);
        Files.createDirectory(directory);
        var metadata =
                new Metadata(
                        3,
                        generation,
                        inputName,
                        fileName,
                        retry != null,
                        Phase.CLAIMING,
                        null,
                        retry == null ? Instant.now().toString() : retry.collectedAt(),
                        retry == null ? null : retry.batchHash());
        writeMetadata(directory.resolve("claim.json"), metadata);
        Metadata processing = finishClaim(directory, metadata);
        return processing == null ? null : claimed(directory, processing);
    }

    void archive(Claimed claimed, boolean complete) throws Exception {
        Metadata metadata = readMetadata(claimed.directory());
        String folder = complete ? "complete" : "error";
        Path archive = safeChild(inputDirectory, folder);
        Files.createDirectories(archive);
        String name = availableArchiveName(archive, metadata.fileName(), false);
        metadata = metadata.phase(complete ? Phase.COMPLETE : Phase.ERROR, name);
        writeMetadata(claimed.directory().resolve("claim.json"), metadata);
        finishArchive(claimed.directory(), metadata);
    }

    Claimed prepareSend(Claimed claimed, String hash) throws IOException {
        Metadata metadata = readMetadata(claimed.directory());
        if (metadata.phase() != Phase.PROCESSING)
            throw invalid(claimed.directory(), "전송 가능한 단계가 아닙니다");
        requireHash(hash, claimed.directory(), false);
        if (metadata.batchHash() != null && !metadata.batchHash().equals(hash))
            throw new IOException(
                    "FILE_BATCH_CHANGED [이전 전송 내용과 다름] 원본="
                            + metadata.fileName()
                            + " 세대="
                            + metadata.generation()
                            + "; 원본과 매핑 설정을 복구하세요. 새 ID로 자동 재전송하지 않습니다");
        metadata = metadata.withHash(hash);
        writeMetadata(claimed.directory().resolve("claim.json"), metadata);
        return claimed(claimed.directory(), metadata);
    }

    private Metadata finishClaim(Path directory, Metadata metadata) throws Exception {
        Path payload = safeChild(directory, "payload");
        Files.createDirectories(payload);
        Path file = safeChild(payload, metadata.fileName());
        if (metadata.retry() && !Files.exists(directory.resolve("retry.json"))) {
            Path sidecar = safeChild(inputDirectory, metadata.inputName() + RETRY_SUFFIX);
            Retry retry = readRetry(sidecar);
            if (!retry.generation().equals(metadata.generation())
                    || !retry.fileName().equals(metadata.fileName())
                    || !retry.collectedAt().equals(metadata.collectedAt())
                    || !retry.batchHash().equals(metadata.batchHash()))
                throw invalid(sidecar, "복귀 세대 또는 원본 파일명이 다릅니다");
            Files.move(sidecar, directory.resolve("retry.json"));
        }
        if (!Files.exists(file)) {
            Path input = safeChild(inputDirectory, metadata.inputName());
            if (!Files.exists(input, LinkOption.NOFOLLOW_LINKS)
                    && discardLostNewClaim(directory, metadata)) return null;
            requireRegular(input);
            try {
                Files.move(input, file);
            } catch (NoSuchFileException e) {
                if (!Files.exists(input, LinkOption.NOFOLLOW_LINKS)
                        && !Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                        && discardLostNewClaim(directory, metadata)) return null;
                throw e;
            }
        }
        requireRegular(file);
        Metadata processing = metadata.phase(Phase.PROCESSING, null);
        writeMetadata(directory.resolve("claim.json"), processing);
        return processing;
    }

    private void finishArchive(Path directory, Metadata metadata) throws Exception {
        Path payload = safeChild(directory, "payload");
        Path file = safeChild(payload, metadata.fileName());
        Path archive =
                safeChild(
                        inputDirectory, metadata.phase() == Phase.COMPLETE ? "complete" : "error");
        Files.createDirectories(archive);
        Path destination = safeChild(archive, metadata.archiveName());
        if (Files.exists(file)) {
            requireRegular(file);
            while (true) {
                boolean occupied =
                        Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                                || Files.exists(
                                        safeChild(archive, metadata.archiveName() + RETRY_SUFFIX),
                                        LinkOption.NOFOLLOW_LINKS);
                if (!occupied) {
                    try {
                        Files.move(file, destination);
                        break;
                    } catch (FileAlreadyExistsException e) {
                        // 확인 직후 다른 수집대상이 같은 보관 이름을 차지해도 기존 파일을 보존합니다.
                    }
                }
                String name = availableArchiveName(archive, metadata.fileName(), true);
                metadata = metadata.phase(metadata.phase(), name);
                writeMetadata(directory.resolve("claim.json"), metadata);
                destination = safeChild(archive, name);
            }
        }
        if (metadata.phase() == Phase.ERROR
                && metadata.batchHash() != null
                && !Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                && returnedRetryMatches(metadata)) {
            // 보관 뒤 종료된 동안 사용자가 원본과 복귀 정보를 되돌렸으면 새 선점 경로로 넘깁니다.
            cleanClaim(directory, payload);
            return;
        }
        requireRegular(destination);
        if (metadata.phase() == Phase.ERROR && metadata.batchHash() != null) {
            Path sidecar = safeChild(archive, metadata.archiveName() + RETRY_SUFFIX);
            var retry = retry(metadata);
            if (Files.exists(sidecar) && !retry.equals(readRetry(sidecar)))
                throw invalid(sidecar, "기존 복귀 정보와 충돌합니다");
            writeMetadata(sidecar, retry);
        }
        cleanClaim(directory, payload);
    }

    private boolean returnedRetryMatches(Metadata metadata) throws IOException {
        Path input = safeChild(inputDirectory, metadata.archiveName());
        Path sidecar = safeChild(inputDirectory, metadata.archiveName() + RETRY_SUFFIX);
        if (!Files.exists(input, LinkOption.NOFOLLOW_LINKS)
                || !Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) return false;
        requireRegular(input);
        if (!retry(metadata).equals(readRetry(sidecar)))
            throw invalid(sidecar, "보관 기록과 복귀 세대·원본명·수집시각·해시가 다릅니다");
        return true;
    }

    private Retry retry(Metadata metadata) {
        return new Retry(
                3,
                source.comCd,
                source.id,
                metadata.generation(),
                metadata.fileName(),
                metadata.collectedAt(),
                metadata.batchHash());
    }

    private void cleanClaim(Path directory, Path payload) throws IOException {
        Files.deleteIfExists(directory.resolve("retry.json"));
        Files.deleteIfExists(payload);
        cleanMetadataTemporaries(directory);
        Files.delete(directory.resolve("claim.json"));
        Files.delete(directory);
    }

    private String availableArchiveName(Path archive, String original, boolean renamed)
            throws IOException {
        int dot = original.lastIndexOf('.');
        String stem = dot > 0 ? original.substring(0, dot) : original;
        String extension = dot > 0 ? original.substring(dot) : "";
        String name = renamed ? stem + "_" + UUID.randomUUID() + extension : original;
        while (Files.exists(safeChild(archive, name), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(
                        safeChild(archive, name + RETRY_SUFFIX), LinkOption.NOFOLLOW_LINKS)) {
            name = stem + "_" + UUID.randomUUID() + extension;
        }
        return name;
    }

    private void discardUninstalledClaim(Path directory) throws IOException {
        final List<Path> contents;
        try (var paths = Files.list(directory)) {
            contents = paths.toList();
        }
        for (var path : contents) {
            if (!isMetadataTemporary(path))
                throw invalid(directory, "claim.json 없이 원본 또는 다른 파일이 남아 있습니다");
            requireRegular(path);
        }
        for (var path : contents) Files.delete(path);
        Files.delete(directory);
    }

    private boolean discardLostNewClaim(Path directory, Metadata metadata) throws IOException {
        if (metadata.retry() || metadata.phase() != Phase.CLAIMING || metadata.batchHash() != null)
            return false;
        if (Files.exists(directory.resolve("retry.json"), LinkOption.NOFOLLOW_LINKS))
            throw invalid(directory, "신규 선점에 복귀 정보가 남아 있습니다");
        Path payload = safeChild(directory, "payload");
        if (Files.exists(payload)) {
            try (var files = Files.list(payload)) {
                if (files.findAny().isPresent()) throw invalid(payload, "선점 실패 폴더에 다른 원본이 남아 있습니다");
            }
        }
        cleanMetadataTemporaries(directory);
        Files.deleteIfExists(payload);
        Files.delete(directory.resolve("claim.json"));
        Files.delete(directory);
        return true;
    }

    private void cleanMetadataTemporaries(Path directory) throws IOException {
        final List<Path> temporary;
        try (var contents = Files.list(directory)) {
            temporary = contents.filter(FileLifecycle::isMetadataTemporary).toList();
        }
        for (var path : temporary) requireRegular(path);
        for (var path : temporary) Files.delete(path);
    }

    private static boolean isMetadataTemporary(Path path) {
        return path.getFileName().toString().matches("metadata-[0-9]+\\.tmp");
    }

    private Metadata readMetadata(Path directory) throws IOException {
        Path path = safeChild(directory, "claim.json");
        requireRegular(path);
        final Metadata metadata;
        try {
            metadata = Json.read(path, Metadata.class);
        } catch (Exception e) {
            throw invalid(path, "처리 메타데이터 형식이 올바르지 않습니다");
        }
        if (metadata == null || metadata.phase() == null) throw invalid(path, "처리 단계가 없습니다");
        requireVersion(metadata.version(), path);
        requireTimestamp(metadata.collectedAt(), path);
        requireHash(metadata.batchHash(), path, !metadata.retry());
        requireUuid(metadata.generation(), path);
        if (!directory.getFileName().toString().equals(metadata.generation()))
            throw invalid(path, "처리 폴더와 세대가 다릅니다");
        safeChild(directory.resolve("payload"), metadata.fileName());
        safeChild(inputDirectory, metadata.inputName());
        boolean archiving = metadata.phase() == Phase.COMPLETE || metadata.phase() == Phase.ERROR;
        if (archiving) safeChild(inputDirectory, metadata.archiveName());
        else if (metadata.archiveName() != null) throw invalid(path, "잘못된 보관 단계입니다");
        return metadata;
    }

    private Retry readRetry(Path path) throws IOException {
        requireRegular(path);
        final Retry retry;
        try {
            retry = Json.read(path, Retry.class);
        } catch (Exception e) {
            throw invalid(path, "복귀 메타데이터 형식이 올바르지 않습니다");
        }
        if (retry == null
                || !source.comCd.equals(retry.comCd())
                || !source.id.equals(retry.sourceId())) throw invalid(path, "회사 또는 수집대상이 다릅니다");
        requireVersion(retry.version(), path);
        requireTimestamp(retry.collectedAt(), path);
        requireHash(retry.batchHash(), path, false);
        requireUuid(retry.generation(), path);
        safeChild(inputDirectory, retry.fileName());
        return retry;
    }

    private Claimed claimed(Path directory, Metadata metadata) {
        return new Claimed(
                directory,
                metadata.generation(),
                metadata.fileName(),
                metadata.collectedAt(),
                metadata.batchHash());
    }

    private static void requireVersion(int version, Path path) throws IOException {
        if (version != 3)
            throw invalid(
                    path,
                    "이전 버전 또는 알 수 없는 처리 기록입니다. 이미 저장된 행을 확인하고 기록과 원본을 별도로 보관한 뒤 파일 전체 단위로 재처리하세요");
    }

    private static void requireTimestamp(String value, Path path) throws IOException {
        try {
            Instant.parse(value);
        } catch (RuntimeException e) {
            throw invalid(path, "고정 수집시각이 올바르지 않습니다");
        }
    }

    private static void requireHash(String value, Path path, boolean optional) throws IOException {
        if (value == null && optional) return;
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw invalid(path, "파일 전송 해시가 올바르지 않습니다");
    }

    private Path safeChild(Path parent, String name) throws IOException {
        if (name == null
                || name.isBlank()
                || name.equals(".")
                || name.equals("..")
                || name.contains("/")
                || name.contains("\\")
                || name.contains(":")
                || Path.of(name).isAbsolute()) throw invalid(parent, "메타데이터에는 파일명만 지정할 수 있습니다");
        Path child = parent.resolve(name).toAbsolutePath().normalize();
        checkContained(child);
        return child;
    }

    private void checkContained(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(inputDirectory)) throw invalid(path, "입력 폴더 밖의 경로입니다");
        for (Path current = absolute;
                !current.equals(inputDirectory);
                current = current.getParent()) {
            if (Files.isSymbolicLink(current))
                throw invalid(current, "처리 메타데이터 경로에 심볼릭 링크를 사용할 수 없습니다");
        }
        Path existing = absolute;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (!existing.toRealPath().startsWith(inputDirectory.toRealPath()))
            throw invalid(path, "실제 경로가 입력 폴더 밖입니다");
    }

    private void requireRegular(Path path) throws IOException {
        checkContained(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw invalid(path, "원본 파일 또는 메타데이터가 없습니다");
    }

    private static void requireUuid(String value, Path path) throws IOException {
        try {
            if (!UUID.fromString(value).toString().equals(value))
                throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw invalid(path, "세대 ID가 올바르지 않습니다");
        }
    }

    private static IOException invalid(Path path, String message) {
        return new IOException(
                "FILE_LIFECYCLE_INVALID [파일 처리 상태 확인 필요] 경로=" + path + " 원인=" + message);
    }

    private static void writeMetadata(Path path, Object value) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), "metadata-", ".tmp");
        try {
            try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap(Json.write(value).getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) output.write(bytes);
                output.force(true);
            }
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

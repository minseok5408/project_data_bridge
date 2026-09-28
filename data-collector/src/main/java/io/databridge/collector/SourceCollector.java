package io.databridge.collector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.logging.Logger;

public final class SourceCollector {
    private static final Logger LOG = Logger.getLogger(SourceCollector.class.getName());
    private final CollectorConfig config;
    private final CollectorConfig.Source source;
    private final Path directory;
    private final Progress progress;
    private final Sender sender;
    private final FileLifecycle lifecycle;

    public SourceCollector(
            CollectorConfig config,
            CollectorConfig.Source source,
            Path root,
            Progress progress,
            Sender sender) {
        this.config = config;
        this.source = source;
        this.directory = root.resolve(source.directory).toAbsolutePath().normalize();
        this.progress = progress;
        this.sender = sender;
        this.lifecycle = new FileLifecycle(directory, source, progress);
    }

    public int poll() throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        progress.requireAtomicFileMode();
        if (!Files.isDirectory(directory))
            throw new IOException(
                    "INPUT_DIRECTORY_UNAVAILABLE [입력 폴더 없음 또는 접근 불가] 수집대상="
                            + source.id
                            + " 경로="
                            + directory);
        var pending = lifecycle.pending();
        var matcher = FileSystems.getDefault().getPathMatcher("glob:" + source.glob);
        Exception failure = null;
        // 새 원본만 가져옵니다. 보관 폴더와 복귀 메타데이터는 수집 대상이 아닙니다.
        try (var paths = Files.list(directory)) {
            for (var file :
                    paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                            .filter(
                                    path ->
                                            !path.getFileName()
                                                    .toString()
                                                    .endsWith(FileLifecycle.RETRY_SUFFIX))
                            .filter(path -> matcher.matches(path.getFileName()))
                            .sorted()
                            .toList()) {
                if (file.getFileName().toString().startsWith("~$")) continue;
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                try {
                    var before = Files.readAttributes(file, BasicFileAttributes.class);
                    if (System.currentTimeMillis() - before.lastModifiedTime().toMillis()
                            < source.settleSeconds * 1000L) continue;
                    var claimed = lifecycle.claim(file);
                    if (claimed != null) pending.add(claimed);
                } catch (Exception e) {
                    failure = addFailure(failure, file, e);
                }
            }
        }
        int count = 0;
        for (var claimed : pending) {
            try {
                count += collect(claimed);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                failure = addFailure(failure, claimed.file(), e);
            }
        }
        if (failure != null) throw failure;
        return count;
    }

    private int collect(FileLifecycle.Claimed claimed) throws Exception {
        Path file = claimed.file();
        final int sent;
        try {
            var before = Files.readAttributes(file, BasicFileAttributes.class);
            if (before.size() > source.maxFileMb * 1024L * 1024L)
                throw new IOException("입력 파일이 maxFileMb 제한을 초과했습니다");
            List<Event> events =
                    switch (source.type) {
                        case "excel" ->
                                new Excel(config, source)
                                        .read(file, claimed.generation(), claimed.collectedAt());
                        case "text" ->
                                new Text(config, source)
                                        .read(file, claimed.generation(), claimed.collectedAt());
                        default ->
                                throw new IllegalArgumentException("지원하지 않는 수집 방식: " + source.type);
                    };
            var after = Files.readAttributes(file, BasicFileAttributes.class);
            if (!unchanged(before, after)) {
                LOG.warning("FILE_CHANGED [파일 검증 중 변경되어 전송 보류] 원본=" + claimed.fileName());
                return 0;
            }
            if (events.isEmpty()) {
                if (claimed.batchHash() != null)
                    throw new IOException("FILE_BATCH_CHANGED [이전 전송 내용과 다름] 재시도 파일에 데이터 행이 없습니다");
                sent = 0;
            } else {
                FileBatch batch = new FileBatch(claimed.generation(), events);
                byte[] body = Json.write(batch).getBytes(StandardCharsets.UTF_8);
                if (body.length > FileBatch.MAX_BYTES)
                    throw new IOException("FILE_BATCH_LIMIT [파일 요청 크기 초과] 최대 16MiB");
                claimed = lifecycle.prepareSend(claimed, Json.hash(body));
                if (!unchanged(after, Files.readAttributes(file, BasicFileAttributes.class))) {
                    LOG.warning("FILE_CHANGED [파일 요청 준비 중 변경되어 전송 보류] 원본=" + claimed.fileName());
                    return 0;
                }
                sender.send(batch);
                if (!unchanged(after, Files.readAttributes(file, BasicFileAttributes.class)))
                    throw new IOException(
                            "FILE_CHANGED [전송 도중 원본 변경] 전송한 내용과 원본을 확인하고 동일 세대로 재시도하세요");
                sent = events.size();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            try {
                // 과거 전송 시도가 있으면 이번 파싱 실패에도 같은 파일 ID와 해시를 보존합니다.
                lifecycle.archive(claimed, false);
                LOG.warning(
                        "FILE_ERROR [오류 파일 이동] 수집대상="
                                + source.id
                                + " 원본="
                                + claimed.fileName()
                                + " 세대="
                                + claimed.generation()
                                + " 원인="
                                + e);
            } catch (Exception moveError) {
                throw new IOException("파일 처리 실패: " + e + "; error 폴더 이동도 실패: " + moveError, e);
            }
            throw e;
        }
        // 확인 응답 뒤 보관만 실패한 경우 처리 상태를 보존하여 재시작 시 안전하게 완료합니다.
        lifecycle.archive(claimed, true);
        LOG.info(
                "FILE_COMPLETE [완료 파일 이동] 수집대상="
                        + source.id
                        + " 원본="
                        + claimed.fileName()
                        + " 세대="
                        + claimed.generation());
        return sent;
    }

    private static Exception addFailure(Exception previous, Path file, Exception cause) {
        var failure = new IOException(file.getFileName() + ": " + cause, cause);
        if (previous == null) return failure;
        previous.addSuppressed(failure);
        return previous;
    }

    private static boolean unchanged(BasicFileAttributes before, BasicFileAttributes after) {
        return before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime());
    }
}

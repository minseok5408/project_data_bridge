package io.databridge.collector;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class CollectorRecoveryTest {
    @TempDir Path root;
    CollectorConfig config;
    CollectorConfig.Source source;
    Progress progress;
    TestReceiver receiver;
    Sender sender;

    @BeforeEach
    void setup() throws Exception {
        config = new CollectorConfig();
        source = new CollectorConfig.Source();
        source.id = "input";
        source.comCd = "test-company";
        source.equipmentId = "EQ01";
        source.type = "text";
        source.directory = ".";
        source.glob = "*.txt";
        source.settleSeconds = 0;
        source.skipLines = 0;
        var field = new CollectorConfig.Field();
        field.column = 1;
        source.fields.put("value", field);
        config.sources.add(source);
        progress = new Progress(root.resolve("progress.json"));
        receiver = new TestReceiver();
        config.endpoint = receiver.endpoint();
        sender = new Sender(config, "recovery-test-token-only");
    }

    @AfterEach
    void close() {
        if (sender != null) sender.close();
        if (receiver != null) receiver.close();
    }

    SourceCollector collector() {
        return new SourceCollector(config, source, root, progress, sender);
    }

    @Test
    void newSameNameFileAndCorrectedParseFailureEachSendAWholeFile() throws Exception {
        Path input = Files.writeString(root.resolve("values.txt"), "1\nbad\n");
        assertThrows(IOException.class, () -> collector().poll());
        assertTrue(values().isEmpty());
        assertEquals(0, receiver.requests.get());
        assertFalse(Files.exists(root.resolve("error/values.txt.retry.json")));

        Files.writeString(input, "1\n2\n");
        progress = new Progress(root.resolve("progress.json"));
        assertEquals(2, collector().poll());
        assertEquals(List.of("1", "2"), values());
        assertTrue(Files.exists(root.resolve("complete/values.txt")));

        Files.move(root.resolve("error/values.txt"), input);
        Files.writeString(input, "1\n3\n");
        progress = new Progress(root.resolve("progress.json"));
        assertEquals(2, collector().poll());
        assertEquals(List.of("1", "2", "1", "3"), values());
        assertEquals(2, receiver.batches.size());
        assertNotEquals(receiver.batches.get(0).eventId(), receiver.batches.get(1).eventId());
        assertTrue(
                receiver.events.stream()
                        .allMatch(event -> event.origin().fileName().equals("values.txt")));
        assertEquals(0, progress.status().get("legacyRowCheckpoints"));
    }

    @Test
    void restartReconcilesCrashAfterArchiveBeforeManifestRemoval() throws Exception {
        Path input = Files.writeString(root.resolve("values.txt"), "1\n");
        Files.writeString(root.resolve("complete"), "block automatic archive");
        assertThrows(IOException.class, () -> collector().poll());
        assertEquals(List.of("1"), values());
        assertEquals(1, receiver.requests.get());

        Files.delete(root.resolve("complete"));
        Files.createDirectory(root.resolve("complete"));
        Path staged = stagedFile("values.txt");
        Path manifest = staged.getParent().getParent().resolve("claim.json");
        var metadata =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        Json.MAPPER.readTree(Files.readString(manifest));
        metadata.put("phase", "COMPLETE").put("archiveName", "values.txt");
        Files.writeString(manifest, Json.write(metadata));
        Files.move(staged, root.resolve("complete/values.txt"));
        // 보관 이동 직후 처리 메타데이터를 지우기 전에 종료된 상황을 재현합니다.
        progress = new Progress(root.resolve("progress.json"));
        assertEquals(0, collector().poll());
        assertEquals(0, progress.status().get("legacyRowCheckpoints"));
        assertEquals(1, receiver.requests.get());
        assertFalse(Files.exists(manifest));

        Files.writeString(input, "1\n2\n");
        assertEquals(2, collector().poll());
        assertEquals(List.of("1", "1", "2"), values());
    }

    @Test
    void operationalTuningPreservesFailedBatchIdentityTimestampAndBytes() throws Exception {
        source.batchLines = 1;
        receiver.failAt = 1;
        Files.writeString(root.resolve("values.txt"), "1\n2\n3\n");
        assertThrows(IOException.class, () -> collector().poll());
        assertTrue(values().isEmpty());
        var retry =
                Json.MAPPER.readTree(Files.readString(root.resolve("error/values.txt.retry.json")));
        assertEquals(3, retry.path("version").asInt());
        assertEquals(receiver.batches.getFirst().eventId(), retry.path("generation").asText());
        assertEquals(
                Json.hash(receiver.batchBodies.getFirst().getBytes(StandardCharsets.UTF_8)),
                retry.path("batchHash").asText());
        assertEquals(
                receiver.batches.getFirst().events().getFirst().collectedAt(),
                retry.path("collectedAt").asText());
        source.batchLines = 10;
        source.maxFileMb = 2;
        restoreRetry("values.txt");
        progress = new Progress(root.resolve("progress.json"));
        assertEquals(3, collector().poll());
        assertEquals(List.of("1", "2", "3"), values());
        assertEquals(2, receiver.requests.get());
        assertEquals(receiver.batchBodies.get(0), receiver.batchBodies.get(1));
        assertFalse(Files.exists(root.resolve("values.txt.retry.json")));
    }

    @Test
    void pathOnlyLegacyCheckpointIsRejectedWithoutGuessingItsGeneration() throws Exception {
        Path input = Files.writeString(root.resolve("values.txt"), "1\n2\n");
        String key = source.comCd + "/" + source.id + "/" + input.toAbsolutePath().normalize();
        String previous =
                Json.write(
                        Map.of(
                                key,
                                Map.of(
                                        "position",
                                        2,
                                        "line",
                                        1,
                                        "digest",
                                        Json.hash("1\n".getBytes(StandardCharsets.UTF_8)),
                                        "signature",
                                        "a".repeat(64))));
        Files.writeString(root.resolve("progress.json"), previous);
        progress = new Progress(root.resolve("progress.json"));
        var error = assertThrows(IOException.class, () -> collector().poll());
        assertTrue(error.getMessage().contains("LEGACY_FILE_PROGRESS"));
        assertTrue(error.getMessage().contains("progress.json"));
        assertTrue(receiver.events.isEmpty());
        assertEquals(previous, Files.readString(root.resolve("progress.json")));
        assertEquals(1, progress.status().get("legacyRowCheckpoints"));
        assertTrue(Files.exists(input));
    }

    @Test
    void versionTwoRowCheckpointIsRejectedBeforeClaimingNewInput() throws Exception {
        Path input = Files.writeString(root.resolve("values.txt"), "1\n2\n");
        String key =
                "file-v2/" + source.comCd + "/" + source.id + "/" + java.util.UUID.randomUUID();
        String previous =
                Json.write(
                        Map.of(
                                key,
                                Map.of(
                                        "position",
                                        2,
                                        "line",
                                        1,
                                        "digest",
                                        Json.hash("1\n".getBytes(StandardCharsets.UTF_8)),
                                        "signature",
                                        "a".repeat(64))));
        Files.writeString(root.resolve("progress.json"), previous);
        progress = new Progress(root.resolve("progress.json"));
        var error = assertThrows(IOException.class, () -> collector().poll());
        assertTrue(error.getMessage().contains("LEGACY_FILE_PROGRESS"));
        assertEquals(previous, Files.readString(root.resolve("progress.json")));
        assertEquals("1\n2\n", Files.readString(input));
        assertFalse(Files.exists(root.resolve(".databridge")));
        assertEquals(0, receiver.requests.get());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 2})
    void legacyClaimManifestRequiresExplicitMigrationWithoutMovingItsPayload(int version)
            throws Exception {
        Path directory = generationDirectory(source);
        Files.createDirectories(directory.resolve("payload"));
        Path payload = Files.writeString(directory.resolve("payload/values.txt"), "1\n2\n");
        var metadata = Json.MAPPER.createObjectNode();
        metadata.put("generation", directory.getFileName().toString())
                .put("inputName", "values.txt")
                .put("fileName", "values.txt")
                .put("retry", false)
                .put("phase", "PROCESSING");
        if (version != 0) metadata.put("version", version);
        String original = Json.write(metadata);
        Files.writeString(directory.resolve("claim.json"), original);
        var error = assertThrows(IOException.class, () -> collector().poll());
        assertTrue(error.getMessage().contains("이전 버전"));
        assertEquals(original, Files.readString(directory.resolve("claim.json")));
        assertEquals("1\n2\n", Files.readString(payload));
        assertEquals(0, receiver.requests.get());
        assertFalse(Files.exists(root.resolve("error")));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 2})
    void legacyRetrySidecarIsPreservedAndCannotStartAnAtomicImport(int version) throws Exception {
        Path input = Files.writeString(root.resolve("values.txt"), "1\n2\n");
        var retry = Json.MAPPER.createObjectNode();
        retry.put("comCd", source.comCd)
                .put("sourceId", source.id)
                .put("generation", java.util.UUID.randomUUID().toString())
                .put("fileName", "values.txt");
        if (version != 0) retry.put("version", version);
        String original = Json.write(retry);
        Path sidecar = Files.writeString(root.resolve("values.txt.retry.json"), original);
        var error = assertThrows(IOException.class, () -> collector().poll());
        assertTrue(error.getMessage().contains("이전 버전"));
        assertEquals(original, Files.readString(sidecar));
        assertEquals("1\n2\n", Files.readString(input));
        assertEquals(0, receiver.requests.get());
        assertFalse(Files.exists(root.resolve(".databridge")));
    }

    @Test
    void missingInputDirectoryIsReportedAndOnceReturnsFailure() throws Exception {
        source.directory = "missing-input";
        var error = assertThrows(IOException.class, () -> collector().poll());
        assertTrue(error.getMessage().contains("INPUT_DIRECTORY_UNAVAILABLE"));
        assertTrue(error.getMessage().contains(root.resolve("missing-input").toString()));

        Path common =
                Files.writeString(
                        root.resolve("collector.json"),
                        Json.write(
                                Map.of(
                                        "sourceFile",
                                        "source.json",
                                        "rootDir",
                                        root.toString(),
                                        "endpoint",
                                        receiver.endpoint())));
        Files.writeString(
                root.resolve("source.json"),
                Json.write(Map.of("collectionType", "text", "sources", List.of(source))));
        Files.writeString(
                root.resolve("application.properties"), "ims.token=recovery-test-token-only\n");
        assertEquals(
                1,
                CollectorApplication.execute(new String[] {"once", "--config", common.toString()}));
        assertEquals(0, receiver.requests.get());
    }

    @Test
    void retryMetadataCannotEscapeInputAndIsNeverCollectedByWildcardGlob() throws Exception {
        source.glob = "*";
        Files.writeString(root.resolve("orphan.txt.retry.json"), "not an input file");
        assertEquals(0, collector().poll());
        Path input = Files.writeString(root.resolve("values.txt"), "1\n");
        Files.writeString(
                root.resolve("values.txt.retry.json"),
                Json.write(
                        Map.of(
                                "version",
                                3,
                                "collectedAt",
                                "2026-09-22T00:00:00Z",
                                "batchHash",
                                "a".repeat(64),
                                "comCd",
                                source.comCd,
                                "sourceId",
                                source.id,
                                "generation",
                                java.util.UUID.randomUUID().toString(),
                                "fileName",
                                "../../outside.txt")));
        var error = assertThrows(IOException.class, () -> collector().poll());
        assertTrue(error.getMessage().contains("FILE_LIFECYCLE_INVALID"));
        assertTrue(Files.exists(input));
        assertEquals(0, receiver.requests.get());
    }

    @Test
    void wildcardInputsNamedLikeInternalMetadataKeepTheirOriginalBytes() throws Exception {
        source.glob = "*";
        Files.writeString(root.resolve("claim.json"), "original claim file");
        Files.writeString(root.resolve("retry.json"), "original retry file");
        assertThrows(IOException.class, () -> collector().poll());
        assertEquals("original claim file", Files.readString(root.resolve("error/claim.json")));
        assertEquals("original retry file", Files.readString(root.resolve("error/retry.json")));
        assertFalse(Files.exists(root.resolve("claim.json")));
        assertFalse(Files.exists(root.resolve("retry.json")));
        assertFalse(Files.exists(root.resolve("error/claim.json.retry.json")));
        assertFalse(Files.exists(root.resolve("error/retry.json.retry.json")));
        assertEquals(0, receiver.requests.get());
    }

    @Test
    void restartFinishesARecordedClaimAndMissingProcessingFileIsNotGuessed() throws Exception {
        String generation = java.util.UUID.randomUUID().toString();
        Path directory =
                root.resolve(".databridge")
                        .resolve(source.comCd)
                        .resolve(source.id)
                        .resolve(generation);
        Files.createDirectories(directory);
        Files.writeString(root.resolve("values.txt"), "1\n");
        Files.writeString(
                directory.resolve("claim.json"),
                Json.write(
                        Map.of(
                                "version",
                                3,
                                "collectedAt",
                                "2026-09-22T00:00:00Z",
                                "generation",
                                generation,
                                "inputName",
                                "values.txt",
                                "fileName",
                                "values.txt",
                                "retry",
                                false,
                                "phase",
                                "CLAIMING")));
        assertEquals(1, collector().poll());
        assertEquals(List.of("1"), values());

        Files.createDirectory(directory);
        Files.writeString(root.resolve("values.txt"), "2\n");
        Files.writeString(
                directory.resolve("claim.json"),
                Json.write(
                        Map.of(
                                "version",
                                3,
                                "collectedAt",
                                "2026-09-22T00:00:00Z",
                                "generation",
                                generation,
                                "inputName",
                                "values.txt",
                                "fileName",
                                "values.txt",
                                "retry",
                                false,
                                "phase",
                                "PROCESSING")));
        var error = assertThrows(IOException.class, () -> collector().poll());
        assertTrue(error.getMessage().contains("FILE_LIFECYCLE_INVALID"));
        assertEquals(List.of("1"), values());
        assertTrue(Files.exists(root.resolve("values.txt")));
    }

    private Path stagedFile(String name) throws IOException {
        try (var files = Files.walk(root.resolve(".databridge"))) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().equals(name))
                    .findFirst()
                    .orElseThrow();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"COMPLETE", "ERROR"})
    void occupiedArchiveIntentIsRenamedAndMetadataTemporaryFilesDoNotBlockCleanup(String phase)
            throws Exception {
        var lifecycle = new FileLifecycle(root, source, progress);
        var claimed = lifecycle.claim(Files.writeString(root.resolve("values.txt"), "1\n"));
        claimed = lifecycle.prepareSend(claimed, "a".repeat(64));
        setArchiveIntent(claimed, phase);
        Path archive =
                Files.createDirectory(
                        root.resolve(phase.equals("COMPLETE") ? "complete" : "error"));
        Files.writeString(archive.resolve("values.txt"), "existing archive");
        Files.writeString(archive.resolve("values.txt.retry.json"), "existing retry metadata");
        Files.writeString(
                Files.createTempFile(claimed.directory(), "metadata-", ".tmp"),
                "interrupted metadata replacement");

        assertTrue(lifecycle.pending().isEmpty());
        assertEquals("existing archive", Files.readString(archive.resolve("values.txt")));
        assertEquals(
                "existing retry metadata",
                Files.readString(archive.resolve("values.txt.retry.json")));
        final Path newArchive;
        try (var files = Files.list(archive)) {
            newArchive =
                    files.filter(path -> path.getFileName().toString().endsWith(".txt"))
                            .filter(path -> !path.getFileName().toString().equals("values.txt"))
                            .findFirst()
                            .orElseThrow();
        }
        assertEquals("1\n", Files.readString(newArchive));
        assertFalse(Files.exists(claimed.directory()));
        if (phase.equals("COMPLETE"))
            assertFalse(
                    Files.exists(
                            newArchive.resolveSibling(
                                    newArchive.getFileName() + FileLifecycle.RETRY_SUFFIX)));
        else {
            var retry =
                    Json.MAPPER.readTree(
                            Files.readString(
                                    newArchive.resolveSibling(
                                            newArchive.getFileName()
                                                    + FileLifecycle.RETRY_SUFFIX)));
            assertEquals(claimed.generation(), retry.path("generation").asText());
            assertEquals(3, retry.path("version").asInt());
            assertEquals(claimed.collectedAt(), retry.path("collectedAt").asText());
            assertEquals("a".repeat(64), retry.path("batchHash").asText());
        }
    }

    @Test
    void crashBeforeInstallingFirstManifestDiscardsOnlyTemporaryMetadataAndReclaimsInput()
            throws Exception {
        Path directory = generationDirectory(source);
        Path temporary = Files.createTempFile(directory, "metadata-", ".tmp");
        Files.writeString(temporary, "partial initial metadata");
        Files.writeString(root.resolve("values.txt"), "1\n");
        assertEquals(1, collector().poll());
        assertEquals(List.of("1"), values());
        assertFalse(Files.exists(directory));
        assertTrue(Files.exists(root.resolve("complete/values.txt")));
    }

    @Test
    void missingManifestWithPayloadIsNotMistakenForAnUninstalledClaim() throws Exception {
        Path directory = generationDirectory(source);
        Path temporary = Files.createTempFile(directory, "metadata-", ".tmp");
        Files.createDirectory(directory.resolve("payload"));
        Path original = Files.writeString(directory.resolve("payload/values.txt"), "1\n");
        var error = assertThrows(IOException.class, () -> collector().poll());
        assertTrue(error.getMessage().contains("FILE_LIFECYCLE_INVALID"));
        assertTrue(Files.exists(temporary));
        assertEquals("1\n", Files.readString(original));
        assertEquals(0, receiver.requests.get());
    }

    @Test
    void losingANewInputClaimDoesNotBlockThatSourceFromProcessingLaterFiles() throws Exception {
        var other = Json.MAPPER.convertValue(source, CollectorConfig.Source.class);
        other.id = "second-source";
        Path lostClaim = generationDirectory(other);
        writeClaimingManifest(lostClaim, false);
        Files.writeString(root.resolve("values.txt"), "1\n");
        assertEquals(1, collector().poll());

        Files.writeString(root.resolve("next.txt"), "2\n");
        var otherCollector = new SourceCollector(config, other, root, progress, sender);
        assertEquals(1, otherCollector.poll());
        assertEquals(List.of("1", "2"), values());
        assertFalse(Files.exists(lostClaim));
        assertEquals("second-source", receiver.events.getLast().sourceId());
        assertEquals(0, otherCollector.poll());
    }

    @Test
    void missingRetryInputIsNotDiscardedAsAnUnacknowledgedNewClaim() throws Exception {
        Path directory = generationDirectory(source);
        writeClaimingManifest(directory, true);
        var error = assertThrows(IOException.class, () -> collector().poll());
        assertTrue(error.getMessage().contains("FILE_LIFECYCLE_INVALID"));
        assertTrue(Files.exists(directory.resolve("claim.json")));
        assertEquals(0, receiver.requests.get());
    }

    @Test
    void concurrentSourcesCanFinishTheSameChosenArchiveNameWithoutOverwriting() throws Exception {
        var other = Json.MAPPER.convertValue(source, CollectorConfig.Source.class);
        other.id = "second-source";
        var first = new FileLifecycle(root, source, progress);
        var second = new FileLifecycle(root, other, progress);
        var firstClaim = first.claim(Files.writeString(root.resolve("values.txt"), "1\n"));
        var secondClaim = second.claim(Files.writeString(root.resolve("values.txt"), "2\n"));
        setArchiveIntent(firstClaim, "COMPLETE");
        setArchiveIntent(secondClaim, "COMPLETE");
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var completed =
                    List.of(first, second).stream()
                            .map(
                                    lifecycle ->
                                            workers.submit(
                                                    () -> {
                                                        ready.countDown();
                                                        start.await();
                                                        return lifecycle.pending();
                                                    }))
                            .toList();
            final boolean allReady;
            try {
                allReady = ready.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                start.countDown();
            }
            assertTrue(allReady);
            for (var result : completed)
                assertTrue(result.get(10, java.util.concurrent.TimeUnit.SECONDS).isEmpty());
        } finally {
            start.countDown();
        }
        try (var files = Files.list(root.resolve("complete"))) {
            var contents =
                    files.map(
                                    path -> {
                                        try {
                                            return Files.readString(path);
                                        } catch (IOException e) {
                                            throw new java.io.UncheckedIOException(e);
                                        }
                                    })
                            .toList();
            assertEquals(java.util.Set.of("1\n", "2\n"), new java.util.HashSet<>(contents));
            assertEquals(2, contents.size());
        }
        assertFalse(Files.exists(firstClaim.directory()));
        assertFalse(Files.exists(secondClaim.directory()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"values.txt", "values_collision.txt"})
    void returnedRetryPairRecoversCrashAfterErrorArchiveBeforeManifestCleanup(String archiveName)
            throws Exception {
        var claimed = simulateReturnedArchivedBatch(archiveName);
        assertEquals(2, collector().poll());
        assertEquals(2, receiver.requests.get());
        assertEquals(List.of("1", "2"), values());
        assertEquals(receiver.batchBodies.get(0), receiver.batchBodies.get(1));
        assertFalse(Files.exists(claimed.directory()));
        assertFalse(Files.exists(root.resolve(archiveName + FileLifecycle.RETRY_SUFFIX)));
        assertTrue(Files.exists(root.resolve("complete/values.txt")));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "generation",
                "fileName",
                "collectedAt",
                "batchHash",
                "comCd",
                "sourceId",
                "missing-file",
                "missing-sidecar",
                "different-name"
            })
    void incompleteOrDifferentReturnedPairDoesNotDiscardTheErrorManifest(String mismatch)
            throws Exception {
        var claimed = simulateReturnedArchivedBatch("values.txt");
        Path input = root.resolve("values.txt");
        Path sidecar = root.resolve("values.txt.retry.json");
        switch (mismatch) {
            case "missing-file" -> Files.delete(input);
            case "missing-sidecar" -> Files.delete(sidecar);
            case "different-name" -> {
                Files.move(input, root.resolve("renamed.txt"));
                Files.move(sidecar, root.resolve("renamed.txt.retry.json"));
            }
            default -> {
                var retry =
                        (com.fasterxml.jackson.databind.node.ObjectNode)
                                Json.MAPPER.readTree(Files.readString(sidecar));
                String replacement =
                        switch (mismatch) {
                            case "generation" -> java.util.UUID.randomUUID().toString();
                            case "collectedAt" -> "2026-09-22T00:00:00Z";
                            case "batchHash" -> "b".repeat(64);
                            default -> "different";
                        };
                retry.put(mismatch, replacement);
                Files.writeString(sidecar, Json.write(retry));
            }
        }
        assertThrows(IOException.class, () -> collector().poll());
        assertTrue(Files.exists(claimed.directory().resolve("claim.json")));
        assertEquals(1, receiver.requests.get());
        assertEquals(List.of("1", "2"), values());
    }

    private FileLifecycle.Claimed simulateReturnedArchivedBatch(String archiveName)
            throws Exception {
        var lifecycle = new FileLifecycle(root, source, progress);
        var claimed = lifecycle.claim(Files.writeString(root.resolve("values.txt"), "1\n2\n"));
        var batch =
                new FileBatch(
                        claimed.generation(),
                        new Text(config, source)
                                .read(claimed.file(), claimed.generation(), claimed.collectedAt()));
        claimed =
                lifecycle.prepareSend(
                        claimed, Json.hash(Json.write(batch).getBytes(StandardCharsets.UTF_8)));
        sender.send(batch);
        Path manifest = claimed.directory().resolve("claim.json");
        var metadata =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        Json.MAPPER.readTree(Files.readString(manifest));
        metadata.put("phase", "ERROR").put("archiveName", archiveName);
        Files.writeString(manifest, Json.write(metadata));
        Files.createDirectories(root.resolve("error"));
        Files.move(claimed.file(), root.resolve("error").resolve(archiveName));
        Files.writeString(
                root.resolve("error").resolve(archiveName + FileLifecycle.RETRY_SUFFIX),
                Json.write(
                        Map.of(
                                "version",
                                3,
                                "comCd",
                                source.comCd,
                                "sourceId",
                                source.id,
                                "generation",
                                claimed.generation(),
                                "fileName",
                                claimed.fileName(),
                                "collectedAt",
                                claimed.collectedAt(),
                                "batchHash",
                                claimed.batchHash())));
        // ERROR 원본과 복귀 정보만 보관된 시점에 종료하고 사용자가 두 파일을 입력 폴더로 옮깁니다.
        restoreRetry(archiveName);
        return claimed;
    }

    private Path generationDirectory(CollectorConfig.Source selected) throws IOException {
        return Files.createDirectories(
                root.resolve(".databridge")
                        .resolve(selected.comCd)
                        .resolve(selected.id)
                        .resolve(java.util.UUID.randomUUID().toString()));
    }

    private void writeClaimingManifest(Path directory, boolean retry) throws IOException {
        var metadata = Json.MAPPER.createObjectNode();
        metadata.put("version", 3)
                .put("collectedAt", "2026-09-22T00:00:00Z")
                .put("generation", directory.getFileName().toString())
                .put("inputName", "values.txt")
                .put("fileName", "values.txt")
                .put("retry", retry)
                .put("phase", "CLAIMING");
        if (retry) metadata.put("batchHash", "a".repeat(64));
        Files.writeString(directory.resolve("claim.json"), Json.write(metadata));
    }

    private void restoreRetry(String name) throws IOException {
        Files.move(root.resolve("error").resolve(name), root.resolve(name));
        Files.move(
                root.resolve("error").resolve(name + FileLifecycle.RETRY_SUFFIX),
                root.resolve(name + FileLifecycle.RETRY_SUFFIX));
    }

    private void setArchiveIntent(FileLifecycle.Claimed claimed, String phase) throws IOException {
        Path manifest = claimed.directory().resolve("claim.json");
        var metadata =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        Json.MAPPER.readTree(Files.readString(manifest));
        metadata.put("phase", phase).put("archiveName", "values.txt");
        Files.writeString(manifest, Json.write(metadata));
    }

    private List<Object> values() {
        return receiver.events.stream().map(event -> event.filePayload().get("value")).toList();
    }
}

package io.databridge.collector;

import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

public final class CollectorApplication {
    private static final Logger LOG = Logger.getLogger(CollectorApplication.class.getName());

    public static void main(String[] args) {
        try {
            System.exit(execute(args));
        } catch (Exception e) {
            System.err.println("실행 오류: " + e);
            System.exit(1);
        }
    }

    public static int execute(String[] args) throws Exception {
        String command = args.length == 0 ? "run" : args[0];
        if (!Set.of("run", "once", "read", "read-once", "validate", "status", "export-config")
                        .contains(command)
                || args.length > 3
                || args.length == 2
                || args.length == 3 && !args[1].equals("--config"))
            throw new IllegalArgumentException(
                    "Usage: data-collector [run|once|read|read-once|validate|status|export-config]"
                            + " [--config path]");
        Path path = Path.of(args.length == 3 ? args[2] : "config/collector.json");
        if (command.equals("export-config")) {
            System.out.println(CollectorConfig.exportCommonSettings(path));
            return 0;
        }
        var loaded = CollectorConfig.load(path);
        var config = loaded.config();
        boolean readOnly = command.equals("read") || command.equals("read-once");
        if (readOnly && !List.of("modbus_tcp").equals(config.collectionType))
            throw new IllegalArgumentException("read/read-once require collectionType=modbus_tcp");
        String settings = path + " + " + config.sourceFile;
        if (command.equals("validate")) {
            ApplicationProperties.loadToken(path);
            System.out.println(
                    "설정 확인 완료: "
                            + settings
                            + " + application.properties (Modbus 장비 "
                            + config.nodes.size()
                            + "개, 파일 수집 대상 "
                            + config.sources.size()
                            + "개)");
            return 0;
        }
        Path state = loaded.root().resolve(config.stateDir).normalize();
        Files.createDirectories(state);
        if (System.getProperty("os.name").startsWith("Windows")
                && System.getProperty("jdk.net.unixdomain.tmpdir") == null)
            System.setProperty("jdk.net.unixdomain.tmpdir", state.toAbsolutePath().toString());
        if (command.equals("status")) {
            System.out.println(Json.write(new Progress(state.resolve("progress.json")).status()));
            return 0;
        }
        try (var channel =
                        FileChannel.open(
                                state.resolve("collector-java.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("같은 상태 저장 폴더를 사용하는 수집기가 이미 실행 중입니다");
            var handler =
                    new FileHandler(
                            state.resolve("collector-%g.log").toString(), 5_000_000, 5, true);
            handler.setEncoding("UTF-8");
            handler.setFormatter(new SimpleFormatter());
            Logger.getLogger("").addHandler(handler);
            try {
                var progress = readOnly ? null : new Progress(state.resolve("progress.json"));
                if (progress != null && !config.sources.isEmpty()) progress.requireAtomicFileMode();
                try (var sender =
                        readOnly
                                ? null
                                : new Sender(config, ApplicationProperties.loadToken(path))) {
                    var jobs = new ArrayList<Job>();
                    for (var node : config.nodes)
                        if (node.enabled)
                            jobs.add(
                                    new Job(
                                            node.nodeId,
                                            node.pollIntervalMs,
                                            () ->
                                                    readOnly
                                                            ? ModbusTcp.sample(config, node)
                                                                    .datas()
                                                                    .size()
                                                            : ModbusTcp.collect(
                                                                    config, node, sender)));
                    for (var source : config.sources)
                        if (source.enabled) {
                            var collector =
                                    new SourceCollector(
                                            config, source, loaded.root(), progress, sender);
                            jobs.add(
                                    new Job(
                                            source.id,
                                            source.pollIntervalSeconds * 1000L,
                                            collector::poll));
                        }
                    if (command.equals("once") || command.equals("read-once")) {
                        boolean ok = true;
                        for (var job : jobs) ok = poll(job, readOnly) && ok;
                        System.out.println(
                                Json.write(
                                        Map.of(
                                                "success",
                                                ok,
                                                "imsSendEnabled",
                                                !readOnly,
                                                "legacyRowCheckpoints",
                                                progress == null
                                                        ? 0
                                                        : progress.status()
                                                                .get("legacyRowCheckpoints"),
                                                "atomicFileDelivery",
                                                true)));
                        return ok ? 0 : 1;
                    }
                    var stopped = new CountDownLatch(1);
                    var executor = Executors.newScheduledThreadPool(Math.max(1, jobs.size()));
                    var shutdown =
                            new Thread(
                                    () -> {
                                        executor.shutdownNow();
                                        try {
                                            executor.awaitTermination(130, TimeUnit.SECONDS);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                        stopped.countDown();
                                    },
                                    "collector-shutdown");
                    Runtime.getRuntime().addShutdownHook(shutdown);
                    try {
                        for (var job : jobs)
                            executor.scheduleWithFixedDelay(
                                    () -> poll(job, readOnly),
                                    0,
                                    job.intervalMs,
                                    TimeUnit.MILLISECONDS);
                        // 시작 로그에서 실제 읽은 설정 파일과 IMS 전송 여부를 확인합니다.
                        LOG.info(
                                "DataBridge 수집기 시작: "
                                        + settings
                                        + (readOnly ? " [읽기 전용: IMS 전송 안 함]" : " [수집 후 IMS 전송]"));
                        stopped.await();
                    } finally {
                        executor.shutdownNow();
                        try {
                            Runtime.getRuntime().removeShutdownHook(shutdown);
                        } catch (IllegalStateException ignored) {
                        }
                    }
                }
            } finally {
                Logger.getLogger("").removeHandler(handler);
                handler.close();
            }
        }
        return 0;
    }

    private record Job(String id, long intervalMs, Callable<Integer> collect) {}

    private static boolean poll(Job job, boolean readOnly) {
        // 상세 전송값은 Sender에 기록하며, 여기서는 이번 실행에서 전송 완료한 데이터 건수를 표시합니다.
        try {
            int count = job.collect.call();
            if (count > 0 && !readOnly)
                LOG.info("수집·전송 완료: 수집대상=" + job.id + " 데이터=" + count + "건");
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            LOG.warning("수집·전송 실패: 수집대상=" + job.id + " 원인=" + e);
            return false;
        }
    }
}

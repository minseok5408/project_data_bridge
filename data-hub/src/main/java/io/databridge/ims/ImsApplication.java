package io.databridge.ims;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public final class ImsApplication {
    public static void main(String[] args) {
        try {
            execute(args);
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void execute(String[] args) throws Exception {
        String command = args.length == 0 ? "run" : args[0];
        if (!Set.of("run", "validate", "export-schema").contains(command)
                || args.length > 3
                || args.length == 2
                || args.length == 3 && !args[1].equals("--config"))
            throw new IllegalArgumentException(
                    "Usage: data-hub [run|validate|export-schema] [--config path]");
        if (command.equals("export-schema")) {
            System.out.println(String.join(";\n\n", Repository.schema()) + ";");
            return;
        }
        var config = args.length == 3 ? ImsConfig.load(Path.of(args[2])) : ImsConfig.load();
        if (command.equals("validate")) {
            System.out.println("IMS configuration OK (database connection not opened)");
            return;
        }
        Path networkTemp = Path.of("runtime").toAbsolutePath().normalize();
        Files.createDirectories(networkTemp);
        if (System.getProperty("os.name").startsWith("Windows")
                && System.getProperty("jdk.net.unixdomain.tmpdir") == null)
            System.setProperty("jdk.net.unixdomain.tmpdir", networkTemp.toString());
        var repository =
                new Repository(
                        config.databaseUrl,
                        config.databaseUser,
                        config.databasePassword,
                        config.initializeSchema,
                        config.modbusMaxGapSeconds);
        System.setProperty("sun.net.httpserver.maxReqTime", "30");
        System.setProperty("sun.net.httpserver.maxRspTime", "30");
        var stopped = new CountDownLatch(1);
        try (var server = new ImsServer(config, repository, config.token)) {
            var shutdown =
                    new Thread(
                            () -> {
                                server.close();
                                stopped.countDown();
                            },
                            "ims-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                server.start();
                System.out.println(
                        "DataBridge Hub listening on " + config.host + ":" + server.port());
                stopped.await();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException ignored) {
                }
            }
        }
    }
}

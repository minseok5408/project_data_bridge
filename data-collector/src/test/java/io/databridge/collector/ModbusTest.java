package io.databridge.collector;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

class ModbusTest {
    @ParameterizedTest
    @ValueSource(ints = {3, 4})
    void fragmentedResponseIsReadCompletely(int function) throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var executor = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(5000);
            var future =
                    executor.submit(
                            () -> {
                                try (var client = server.accept()) {
                                    byte[] request = client.getInputStream().readNBytes(12);
                                    byte[] response = {
                                        request[0],
                                        request[1],
                                        0,
                                        0,
                                        0,
                                        7,
                                        1,
                                        (byte) function,
                                        4,
                                        0,
                                        42,
                                        (byte) 255,
                                        (byte) 255
                                    };
                                    for (byte b : response) {
                                        client.getOutputStream().write(b);
                                        client.getOutputStream().flush();
                                        Thread.sleep(2);
                                    }
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            });
            var node = new CollectorConfig.Node();
            node.host = "127.0.0.1";
            node.port = server.getLocalPort();
            var readBlock = new CollectorConfig.ReadBlock();
            readBlock.functionCode = function;
            readBlock.registerCount = 2;
            assertArrayEquals(new int[] {42, 65535}, ModbusTcp.read(node, readBlock));
            future.get(5, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void malformedAndExceptionResponsesAreRejected(int mode) throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var executor = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(5000);
            var future =
                    executor.submit(
                            () -> {
                                try (var client = server.accept()) {
                                    byte[] request = client.getInputStream().readNBytes(12);
                                    byte[] response =
                                            mode == 0
                                                    ? new byte[] {
                                                        request[0],
                                                        request[1],
                                                        0,
                                                        0,
                                                        0,
                                                        3,
                                                        1,
                                                        (byte) 131,
                                                        2
                                                    }
                                                    : new byte[] {
                                                        request[0],
                                                        (byte) (request[1] + (mode == 1 ? 1 : 0)),
                                                        0,
                                                        0,
                                                        0,
                                                        5,
                                                        1,
                                                        3,
                                                        2,
                                                        0,
                                                        42
                                                    };
                                    client.getOutputStream()
                                            .write(
                                                    mode == 2
                                                            ? Arrays.copyOf(response, 9)
                                                            : response);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            });
            var node = new CollectorConfig.Node();
            node.host = "127.0.0.1";
            node.port = server.getLocalPort();
            var readBlock = new CollectorConfig.ReadBlock();
            readBlock.registerCount = 1;
            assertThrows(IOException.class, () -> ModbusTcp.read(node, readBlock));
            future.get(5, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"accepted", "accepted_partial", "ignored_stale"})
    void sendsAllRegistersUnchangedWithOriginalBlockAddress(String acknowledgementStatus)
            throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var executor = Executors.newSingleThreadExecutor();
                var receiver = new TestReceiver()) {
            receiver.acknowledgementStatus = acknowledgementStatus;
            server.setSoTimeout(5000);
            var future =
                    executor.submit(
                            () -> {
                                try (var client = server.accept()) {
                                    var input = new DataInputStream(client.getInputStream());
                                    int id = input.readUnsignedShort();
                                    assertEquals(0, input.readUnsignedShort());
                                    assertEquals(6, input.readUnsignedShort());
                                    assertEquals(1, input.readUnsignedByte());
                                    assertEquals(3, input.readUnsignedByte());
                                    assertEquals(1, input.readUnsignedShort());
                                    assertEquals(3, input.readUnsignedShort());
                                    var output = new DataOutputStream(client.getOutputStream());
                                    output.writeShort(id);
                                    output.writeShort(0);
                                    output.writeShort(9);
                                    output.writeByte(1);
                                    output.writeByte(3);
                                    output.writeByte(6);
                                    output.writeShort(0);
                                    output.writeShort(32768);
                                    output.writeShort(65535);
                                    output.flush();
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            });
            var config = new CollectorConfig();
            config.endpoint = receiver.endpoint();
            var node = new CollectorConfig.Node();
            node.nodeId = "ND01";
            node.comCd = "company";
            node.host = "127.0.0.1";
            node.port = server.getLocalPort();
            var readBlock = new CollectorConfig.ReadBlock();
            readBlock.startAddress = 1;
            readBlock.registerCount = 3;
            node.readBlocks.add(readBlock);
            config.nodes.add(node);
            config.validate();
            try (var sender = new Sender(config, "modbus-test-token-1234")) {
                assertEquals(
                        acknowledgementStatus.equals("accepted") ? 1 : 0,
                        ModbusTcp.collect(config, node, sender));
            }
            future.get(5, TimeUnit.SECONDS);
            assertEquals(1, receiver.modbus.size());
            assertTrue(receiver.events.isEmpty());
            var packet = receiver.modbus.getFirst();
            var data = packet.datas().getFirst();
            assertEquals("ND01", packet.nodeId());
            assertEquals(node.comCd, packet.comCd());
            assertEquals(1, data.startAddress());
            assertEquals(List.of(0, 32768, 65535), data.vals());
            assertFalse(Json.write(packet).contains("measurements"));
        }
    }

    @Test
    void readOnlyCliLogsValuesWithoutHttpTokenOrProgress(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        try (var plc = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                var executor = Executors.newSingleThreadExecutor();
                var receiver = new TestReceiver()) {
            plc.setSoTimeout(5000);
            var simulated =
                    executor.submit(
                            () -> {
                                try (var connection = plc.accept()) {
                                    byte[] request = connection.getInputStream().readNBytes(12);
                                    byte[] response = {
                                        request[0],
                                        request[1],
                                        0,
                                        0,
                                        0,
                                        7,
                                        1,
                                        3,
                                        4,
                                        0,
                                        42,
                                        (byte) 255,
                                        (byte) 255
                                    };
                                    connection.getOutputStream().write(response);
                                } catch (IOException e) {
                                    throw new java.io.UncheckedIOException(e);
                                }
                            });
            var common = temp.resolve("collector.json");
            java.nio.file.Files.writeString(
                    common,
                    Json.write(
                            Map.of(
                                    "rootDir",
                                    temp.toString(),
                                    "endpoint",
                                    receiver.endpoint(),
                                    "sourceFile",
                                    "read-test.json")));
            java.nio.file.Files.writeString(
                    temp.resolve("read-test.json"),
                    """
                    {"collectionType":"modbus_tcp","nodes":[{"nodeId":"ND01","com_cd":"company","host":"127.0.0.1","port":%d,"readBlocks":[{"startAddress":1,"registerCount":2}]}]}
                    """
                            .formatted(plc.getLocalPort()));
            java.nio.file.Files.writeString(temp.resolve("application.properties"), "ims.token=\n");
            assertEquals(
                    0,
                    CollectorApplication.execute(
                            new String[] {"read-once", "--config", common.toString()}));
            simulated.get(5, TimeUnit.SECONDS);
            assertEquals(0, receiver.requests.get());
            assertFalse(java.nio.file.Files.exists(temp.resolve("runtime/progress.json")));
            String log = java.nio.file.Files.readString(temp.resolve("runtime/collector-0.log"));
            assertTrue(log.contains("MODBUS_READ_OK"));
            assertTrue(log.contains("vals=[42, 65535]"));
            assertFalse(log.contains("IMS_SEND_OK"));
        }
    }

    @Test
    void modbusConnectionFailureNamesTheReadStage() throws Exception {
        var config = new CollectorConfig();
        var node = new CollectorConfig.Node();
        node.nodeId = "ND01";
        node.comCd = "company";
        node.host = "127.0.0.1";
        node.timeoutSeconds = 1;
        var block = new CollectorConfig.ReadBlock();
        block.startAddress = 1;
        block.registerCount = 10;
        node.readBlocks.add(block);
        try (var unusedPort = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            node.port = unusedPort.getLocalPort();
        }
        var error = assertThrows(IOException.class, () -> ModbusTcp.sample(config, node));
        assertTrue(error.getMessage().contains("MODBUS_READ_FAILED"));
        assertTrue(error.getMessage().contains("startAddress=1 registerCount=10"));
        assertInstanceOf(ConnectException.class, error.getCause());
    }

    @Test
    void invalidBlocksAndOutOfRangeRawValuesAreRejected() {
        var config = new CollectorConfig();
        var node = new CollectorConfig.Node();
        node.nodeId = "ND01";
        node.comCd = "company";
        node.host = "127.0.0.1";
        config.nodes.add(node);
        var readBlock = new CollectorConfig.ReadBlock();
        readBlock.registerCount = 2;
        readBlock.startAddress = 65535;
        node.readBlocks.add(readBlock);
        assertThrows(IllegalArgumentException.class, config::validate);
        readBlock.startAddress = 0;
        readBlock.functionCode = 16;
        assertThrows(IllegalArgumentException.class, config::validate);
        readBlock.functionCode = 3;
        node.readBlocks.add(readBlock);
        assertThrows(IllegalArgumentException.class, config::validate);
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModbusData.Data("2026-09-22T13:00:00+09:00", 1, 3, 0, List.of(65536)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModbusData.Data("2026-09-22T13:00:00+09:00", 1, 3, 0, List.of(-1)));
    }
}

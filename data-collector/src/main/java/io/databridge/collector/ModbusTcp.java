package io.databridge.collector;

import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/** 레지스터 원시값을 그대로 읽고 전송합니다. 생산량 계산은 IMS에서 처리합니다. */
public final class ModbusTcp {
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final Logger LOG = Logger.getLogger(ModbusTcp.class.getName());

    private ModbusTcp() {}

    public static int collect(CollectorConfig config, CollectorConfig.Node node, Sender sender)
            throws Exception {
        var data = sample(config, node);
        var ack = sender.send(data);
        // 부분 반영 응답에는 반영 건수가 없으므로 전체 건수를 성공으로 집계하지 않습니다.
        return "accepted_partial".equals(ack.status()) || "ignored_stale".equals(ack.status())
                ? 0
                : data.datas().size();
    }

    /** 레지스터를 읽어 로그에 기록합니다. 이 메서드 자체는 IMS에 전송하지 않습니다. */
    public static ModbusData sample(CollectorConfig config, CollectorConfig.Node node)
            throws Exception {
        var datas = new ArrayList<ModbusData.Data>();
        for (var readBlock : node.readBlocks) {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException("Modbus 수집이 중단되었습니다");
            String target =
                    node.nodeId
                            + " com_cd="
                            + node.comCd
                            + " host="
                            + node.host
                            + ":"
                            + node.port
                            + " unitId="
                            + readBlock.unitId
                            + " functionCode="
                            + readBlock.functionCode
                            + " startAddress="
                            + readBlock.startAddress
                            + " registerCount="
                            + readBlock.registerCount;
            final int[] registers;
            try {
                registers = read(node, readBlock);
            } catch (IOException e) {
                throw new IOException(
                        "MODBUS_READ_FAILED [Modbus TCP 읽기 실패] " + target + " 원인=" + e, e);
            }
            // vals는 startAddress부터 순서대로 읽은 값입니다. 이 로그는 읽기 성공이며 IMS 저장 여부는 IMS_SEND_OK로 확인합니다.
            LOG.info(
                    "MODBUS_READ_OK [Modbus TCP 읽기 완료] "
                            + target
                            + " 원시값 vals="
                            + Arrays.toString(registers));
            Integer statusAddress =
                    config.statusAddress >= readBlock.startAddress
                                    && config.statusAddress
                                            < readBlock.startAddress + readBlock.registerCount
                            ? config.statusAddress
                            : null;
            datas.add(
                    new ModbusData.Data(
                            OffsetDateTime.now(ZoneId.of(config.timezone)).toString(),
                            readBlock.unitId,
                            readBlock.functionCode,
                            readBlock.startAddress,
                            Arrays.stream(registers).boxed().toList(),
                            statusAddress,
                            node.counterWordOrder));
        }
        return new ModbusData(UUID.randomUUID().toString(), node.comCd, node.nodeId, datas);
    }

    public static int[] read(CollectorConfig.Node node, CollectorConfig.ReadBlock readBlock)
            throws IOException {
        int transaction = IDS.incrementAndGet() & 0xffff;
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(node.host, node.port), node.timeoutSeconds * 1000);
            socket.setSoTimeout(node.timeoutSeconds * 1000);
            var output = new DataOutputStream(socket.getOutputStream());
            output.writeShort(transaction);
            output.writeShort(0);
            output.writeShort(6);
            output.writeByte(readBlock.unitId);
            output.writeByte(readBlock.functionCode);
            output.writeShort(readBlock.startAddress);
            output.writeShort(readBlock.registerCount);
            output.flush();
            var input = new DataInputStream(socket.getInputStream());
            int receivedId = input.readUnsignedShort(),
                    protocol = input.readUnsignedShort(),
                    length = input.readUnsignedShort(),
                    unit = input.readUnsignedByte();
            if (receivedId != transaction
                    || protocol != 0
                    || unit != readBlock.unitId
                    || length < 3
                    || length > 254)
                throw new IOException("Modbus 응답 헤더의 요청 번호·프로토콜·장치 번호·길이가 올바르지 않습니다");
            byte[] body = new byte[length - 1];
            input.readFully(body);
            int function = body[0] & 0xff;
            if (function == (readBlock.functionCode | 0x80))
                throw new IOException("Modbus 장치 오류: 예외코드=" + (body[1] & 0xff));
            if (function != readBlock.functionCode
                    || (body[1] & 0xff) != readBlock.registerCount * 2
                    || body.length != 2 + readBlock.registerCount * 2)
                throw new IOException("Modbus 응답의 데이터 길이 또는 기능 코드가 올바르지 않습니다");
            int[] registers = new int[readBlock.registerCount];
            for (int i = 0; i < registers.length; i++)
                registers[i] = ((body[2 + i * 2] & 0xff) << 8) | (body[3 + i * 2] & 0xff);
            return registers;
        }
    }
}

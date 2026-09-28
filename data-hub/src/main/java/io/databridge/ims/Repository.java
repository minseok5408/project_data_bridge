package io.databridge.ims;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 여러 회사의 데이터를 처리하며, 파일 JSON과 Modbus 생산 구간은 별도로 저장합니다. */
public final class Repository implements HubRepository {
    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(Repository.class.getName());

    public static final class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
    private static final String INSERT_FILE_ROW =
            """
            INSERT INTO data_json(
                com_cd,equipment_id,source_code,collection_type,event_id,observed_at,
                received_at,file_name,file_extension,item_code,cmnt,payload)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            """;
    private final String url, user, password;
    private final int maxGapSeconds;
    private final Clock clock;

    public Repository(String url, String user, String password) throws Exception {
        this(url, user, password, true);
    }

    public Repository(String url, String user, String password, boolean initializeSchema)
            throws Exception {
        this(url, user, password, initializeSchema, 15);
    }

    public Repository(
            String url, String user, String password, boolean initializeSchema, int maxGapSeconds)
            throws Exception {
        this(url, user, password, initializeSchema, maxGapSeconds, Clock.systemUTC());
    }

    Repository(
            String url,
            String user,
            String password,
            boolean initializeSchema,
            int maxGapSeconds,
            Clock clock)
            throws Exception {
        if (url == null || !url.startsWith("jdbc:mariadb://"))
            throw new IllegalArgumentException("MariaDB JDBC URL required");
        if (maxGapSeconds < 1 || maxGapSeconds > 86400)
            throw new IllegalArgumentException("Invalid Modbus gap limit");
        this.url = url;
        this.user = user;
        this.password = password;
        this.maxGapSeconds = maxGapSeconds;
        this.clock = Objects.requireNonNull(clock);
        try (var db = connect();
                var s = db.createStatement()) {
            if (tableExists(db, "events") || tableExists(db, "data_value"))
                throw new SQLException(
                        "Remove the incompatible events/data_value tables before starting the hub");
            if (tableExists(db, "data")
                    || tableExists(db, "modbus_state")
                            && columnExists(db, "modbus_state", "last_data_id"))
                throw new SQLException(
                        "Migrate data to data_json and detach modbus_state before starting IMS");
            if (tableExists(db, "company") || tableExists(db, "source"))
                throw new SQLException(
                        "Reset the old company/source schema before starting the equipment hub");
            if (initializeSchema) for (String ddl : schema()) s.execute(ddl);
            validateSchema(db);
        }
    }

    public static List<String> schema() {
        return List.of(
                """
                CREATE TABLE IF NOT EXISTS equipment (
                    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '다른 테이블에서 참조하는 설비 번호',
                    com_cd VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '회사 코드: 설정의 com_cd',
                    equipment_code VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '설정의 equipmentId 또는 nodeId',
                    created_at DATETIME NOT NULL COMMENT '최초 수신 시각 (UTC)',
                    PRIMARY KEY (id),
                    UNIQUE KEY uq_equipment_code (com_cd,equipment_code),
                    UNIQUE KEY uq_equipment_tenant_id (com_cd,id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='회사별 설비: 같은 설비의 Excel/Text/Modbus가 공유'
                """,
                """
                CREATE TABLE IF NOT EXISTS data_json (
                    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '저장 순번',
                    com_cd VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    equipment_id BIGINT UNSIGNED NOT NULL COMMENT 'equipment.id',
                    source_code VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '수집 설정 sources.id',
                    collection_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'excel 또는 text',
                    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '중복 전송 확인 UUID',
                    observed_at DATETIME NOT NULL COMMENT '관측 시각 (UTC)',
                    received_at DATETIME NOT NULL COMMENT '허브 저장 시각 (UTC)',
                    file_name VARCHAR(255) NULL COMMENT '확장자를 포함한 원본 파일명',
                    file_extension VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '원본 파일 확장자: txt, xls, xlsx',
                    item_code VARCHAR(255) NULL COMMENT '파일의 항목코드',
                    cmnt TEXT NULL COMMENT '파일의 비고',
                    payload JSON NOT NULL COMMENT '선택한 항목의 제목과 값 객체',
                    PRIMARY KEY (id),
                    UNIQUE KEY uq_json_delivery (com_cd,event_id),
                    KEY ix_json_equipment_id (com_cd,equipment_id,id),
                    KEY ix_json_observed (com_cd,observed_at,id),
                    CONSTRAINT fk_json_equipment FOREIGN KEY (com_cd,equipment_id) REFERENCES equipment(com_cd,id),
                    CONSTRAINT ck_json_type CHECK (collection_type IN ('excel','text')),
                    CONSTRAINT ck_json_extension CHECK (file_extension IS NULL OR file_extension IN ('txt','xls','xlsx')),
                    CONSTRAINT ck_json_payload CHECK (JSON_TYPE(payload)='OBJECT' AND JSON_LENGTH(payload) BETWEEN 1 AND 256)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Excel/Text 전송 한 건당 JSON 한 행'
                """,
                """
                CREATE TABLE IF NOT EXISTS data_modbus (
                    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                    com_cd VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    equipment_id BIGINT UNSIGNED NOT NULL COMMENT 'equipment.id',
                    unit_id SMALLINT UNSIGNED NOT NULL,
                    function_code TINYINT UNSIGNED NOT NULL,
                    started_at DATETIME NOT NULL COMMENT '생산 시작 관측 시각 (UTC)',
                    ended_at DATETIME NULL COMMENT '0으로 생산 중지를 확인한 시각 (UTC), 미확인 시 NULL',
                    last_observed_at DATETIME NOT NULL COMMENT '이 구간의 마지막 확인 시각 (UTC)',
                    production_count BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '누적값 차이로 계산한 구간 생산수량',
                    run_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    end_reason VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uq_modbus_equipment_stream (com_cd,equipment_id,unit_id,function_code,id),
                    KEY ix_modbus_equipment_time (com_cd,equipment_id,started_at,id),
                    CONSTRAINT fk_modbus_equipment FOREIGN KEY (com_cd,equipment_id) REFERENCES equipment(com_cd,id),
                    CONSTRAINT ck_modbus_run_state CHECK (run_state IN ('running','completed','interrupted'))
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Modbus 생산 구간별 시작·종료·생산수량'
                """,
                """
                CREATE TABLE IF NOT EXISTS modbus_state (
                    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                    com_cd VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    equipment_id BIGINT UNSIGNED NOT NULL COMMENT 'equipment.id',
                    unit_id SMALLINT UNSIGNED NOT NULL,
                    function_code TINYINT UNSIGNED NOT NULL,
                    status TINYINT UNSIGNED NULL COMMENT '0=생산 중지, 1=생산 중, NULL=알 수 없음',
                    counter_value BIGINT UNSIGNED NOT NULL COMMENT '최근 두 레지스터를 합친 누적값',
                    current_run_id BIGINT UNSIGNED NULL,
                    status_address SMALLINT UNSIGNED NOT NULL COMMENT '생산 상태 레지스터 주소',
                    counter_word_order VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    last_event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '최근 적용 전송 UUID',
                    last_event_hash BINARY(32) NOT NULL COMMENT '최근 전송 내용 비교용 SHA-256, 원문은 저장하지 않음',
                    observed_nano INT UNSIGNED NOT NULL COMMENT '역순 판정용 관측 시각의 나노초 부분',
                    observed_at DATETIME NOT NULL COMMENT '최근 관측 시각 (UTC)',
                    received_at DATETIME NOT NULL COMMENT '최근 정상 순서의 수신 시각 (UTC)',
                    PRIMARY KEY (id),
                    UNIQUE KEY uq_state_stream (com_cd,equipment_id,unit_id,function_code),
                    KEY ix_state_delivery (com_cd,last_event_id),
                    CONSTRAINT ck_state_status CHECK (status IS NULL OR status IN (0,1)),
                    CONSTRAINT ck_state_counter CHECK (counter_value <= 4294967295),
                    CONSTRAINT ck_state_nano CHECK (observed_nano <= 999999999),
                    CONSTRAINT ck_state_word_order CHECK (counter_word_order IN ('HIGH_LOW','LOW_HIGH')),
                    CONSTRAINT fk_state_equipment FOREIGN KEY (com_cd,equipment_id) REFERENCES equipment(com_cd,id),
                    CONSTRAINT fk_state_run FOREIGN KEY (com_cd,equipment_id,unit_id,function_code,current_run_id)
                        REFERENCES data_modbus(com_cd,equipment_id,unit_id,function_code,id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='설비별 최근 생산 상태와 다음 수량 계산 기준'
                """,
                """
                CREATE TABLE IF NOT EXISTS file_import (
                    com_cd VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '회사 코드',
                    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '파일 전체 전송 UUID',
                    request_hash BINARY(32) NOT NULL COMMENT '파일 전송 전체 내용의 SHA-256',
                    row_count INT UNSIGNED NOT NULL COMMENT '함께 저장한 데이터 행 수',
                    received_at DATETIME NOT NULL COMMENT '파일 전체 저장 완료 시각 (UTC)',
                    PRIMARY KEY (com_cd,event_id),
                    CONSTRAINT ck_import_row_count CHECK (row_count BETWEEN 1 AND 10000)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='파일 전체 저장 확인 및 재전송 내용 비교'
                """);
    }

    private Connection connect() throws SQLException {
        var properties = new Properties();
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "10000");
        properties.setProperty("socketTimeout", "30000");
        return DriverManager.getConnection(url, properties);
    }

    public void health() throws SQLException {
        try (var db = connect();
                var s = db.createStatement()) {
            s.executeQuery("SELECT 1").close();
        }
    }

    public String ingest(Event event) throws Exception {
        return store(
                event.eventId(),
                event.comCd(),
                event.sourceId(),
                event.equipmentId(),
                event.sourceType(),
                OffsetDateTime.parse(event.observedAt()).toInstant(),
                Json.write(event.filePayload()),
                event.fileName(),
                event.fileExtension(),
                event.fileItemCode(),
                event.fileCmnt(),
                List.of());
    }

    public String ingest(ModbusData packet) throws Exception {
        var readings = ModbusProduction.decode(packet);
        if (readings.isEmpty())
            throw new ValidationException(
                    "datas", "Modbus packet requires a status register and two counter registers");
        ProductionCalculator.validateObservationTimes(packet, clock.instant());
        return store(
                packet.eventId(),
                packet.comCd(),
                packet.nodeId(),
                packet.nodeId(),
                "modbus_tcp",
                readings.getFirst().observed(),
                Json.write(packet),
                null,
                null,
                null,
                null,
                readings);
    }

    public String ingest(FileBatch batch) throws Exception {
        byte[] request = Json.write(batch).getBytes(StandardCharsets.UTF_8);
        ValidationException.require(
                request.length <= FileBatch.MAX_BYTES,
                "events",
                "Serialized file batch exceeds 16 MiB");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(request);
        var first = batch.events().getFirst();
        var rows = batch.events().stream().map(FileRow::new).toList();
        try (var db = connect()) {
            acquireDeliveryLock(db, first.comCd(), batch.eventId());
            db.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            db.setAutoCommit(false);
            try {
                var receipt =
                        one(
                                db,
                                "SELECT request_hash,row_count FROM file_import WHERE com_cd=? AND"
                                        + " event_id=?",
                                first.comCd(),
                                batch.eventId());
                if (receipt != null) {
                    if (!MessageDigest.isEqual(digest, (byte[]) receipt.get("request_hash"))
                            || ((Number) receipt.get("row_count")).intValue() != rows.size()) {
                        throw new Conflict("File eventId already exists with different content");
                    }
                    db.commit();
                    return "duplicate";
                }
                LocalDateTime received = databaseTime(clock.instant());
                long equipmentId = equipment(db, first.comCd(), first.equipmentId(), received);
                one(
                        db,
                        "SELECT id FROM equipment WHERE com_cd=? AND id=? FOR UPDATE",
                        first.comCd(),
                        equipmentId);
                // 같은 설비의 다른 파일 저장이 끝난 뒤 파일 ID와 기존 행 ID를 비교합니다.
                if (one(
                                db,
                                """
                                SELECT event_id FROM data_json WHERE com_cd=? AND event_id=?
                                UNION ALL
                                SELECT last_event_id FROM modbus_state WHERE com_cd=? AND last_event_id=?
                                """,
                                first.comCd(),
                                batch.eventId(),
                                first.comCd(),
                                batch.eventId())
                        != null) {
                    throw new Conflict("File eventId is already used by another delivery");
                }
                int existing = matchingFileRows(db, first.comCd(), rows);
                if (existing != 0 && existing != rows.size()) {
                    throw new Conflict("Only part of this file batch was previously stored");
                }
                if (existing == 0) {
                    try (var statement = db.prepareStatement(INSERT_FILE_ROW)) {
                        for (var row : rows) {
                            bind(statement, fileParameters(row, equipmentId, received));
                            statement.addBatch();
                        }
                        statement.executeBatch();
                    }
                }
                update(
                        db,
                        """
                        INSERT INTO file_import(com_cd,event_id,request_hash,row_count,received_at)
                        VALUES(?,?,?,?,?)
                        """,
                        first.comCd(),
                        batch.eventId(),
                        digest,
                        rows.size(),
                        received);
                db.commit();
                return existing == 0 ? "accepted" : "duplicate";
            } catch (Exception e) {
                db.rollback();
                if (e instanceof SQLException sql && sql.getErrorCode() == 1062) {
                    throw new Conflict("A file row eventId is already used by another delivery");
                }
                throw e;
            }
        }
    }

    private record FileRow(Event event, Instant observed, String payload) {
        FileRow(Event event) {
            this(
                    event,
                    OffsetDateTime.parse(event.observedAt()).toInstant(),
                    Json.write(event.filePayload()));
        }
    }

    private static Object[] fileParameters(FileRow row, long equipmentId, LocalDateTime received) {
        var event = row.event();
        return new Object[] {
            event.comCd(),
            equipmentId,
            event.sourceId(),
            event.sourceType(),
            event.eventId(),
            databaseTime(row.observed()),
            received,
            event.fileName(),
            event.fileExtension(),
            event.fileItemCode(),
            event.fileCmnt(),
            row.payload()
        };
    }

    /** 기존 행은 묶어서 조회하되, 일부만 존재하는 파일을 이어 붙이지 않습니다. */
    private static int matchingFileRows(Connection db, String comCd, List<FileRow> rows)
            throws SQLException {
        int matching = 0;
        for (int start = 0; start < rows.size(); start += 500) {
            var chunk = rows.subList(start, Math.min(rows.size(), start + 500));
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            var parameters = new ArrayList<Object>();
            parameters.add(comCd);
            chunk.forEach(row -> parameters.add(row.event().eventId()));
            var stored =
                    query(
                            db,
                            """
                            SELECT d.event_id,d.payload,d.observed_at,d.file_name,d.file_extension,
                                   d.item_code,d.cmnt,d.source_code,e.equipment_code,d.collection_type
                            FROM data_json d
                            JOIN equipment e ON e.com_cd=d.com_cd AND e.id=d.equipment_id
                            WHERE d.com_cd=? AND d.event_id IN (
                            """
                                    + placeholders
                                    + ")",
                            parameters.toArray());
            var byId = new HashMap<String, Map<String, Object>>();
            for (var row : stored) byId.put((String) row.get("event_id"), row);
            for (var row : chunk) {
                var previous = byId.get(row.event().eventId());
                if (previous == null) continue;
                var event = row.event();
                verifyFileContent(
                        previous,
                        row.payload(),
                        event.sourceId(),
                        event.equipmentId(),
                        event.sourceType(),
                        row.observed(),
                        event.fileName(),
                        event.fileExtension(),
                        event.fileItemCode(),
                        event.fileCmnt());
                matching++;
            }
            if (!query(
                                    db,
                                    "SELECT event_id FROM file_import WHERE com_cd=? AND event_id"
                                            + " IN ("
                                            + placeholders
                                            + ")",
                                    parameters.toArray())
                            .isEmpty()
                    || !query(
                                    db,
                                    "SELECT last_event_id FROM modbus_state WHERE com_cd=? AND"
                                            + " last_event_id IN ("
                                            + placeholders
                                            + ")",
                                    parameters.toArray())
                            .isEmpty()) {
                throw new Conflict("A file row eventId is already used by another delivery");
            }
        }
        return matching;
    }

    private String store(
            String eventId,
            String comCd,
            String source,
            String equipment,
            String type,
            Instant observed,
            String payload,
            String fileName,
            String fileExtension,
            String itemCode,
            String cmnt,
            List<ModbusProduction.Reading> readings)
            throws Exception {
        try (var db = connect()) {
            acquireDeliveryLock(db, comCd, eventId);
            db.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            db.setAutoCommit(false);
            try {
                byte[] digest =
                        MessageDigest.getInstance("SHA-256")
                                .digest(payload.getBytes(StandardCharsets.UTF_8));
                if (knownDelivery(
                        db,
                        comCd,
                        eventId,
                        payload,
                        digest,
                        source,
                        equipment,
                        type,
                        observed,
                        fileName,
                        fileExtension,
                        itemCode,
                        cmnt)) {
                    db.commit();
                    return "duplicate";
                }
                LocalDateTime received = databaseTime(clock.instant());
                long equipmentId = equipment(db, comCd, equipment, received);
                // 설비별 갱신을 직렬화합니다. 같은 설비의 여러 수집 경로도 하나의 설비 행을 공유합니다.
                one(
                        db,
                        "SELECT id FROM equipment WHERE com_cd=? AND id=? FOR UPDATE",
                        comCd,
                        equipmentId);
                // 잠금을 얻은 뒤 다시 확인합니다. 동시에 들어온 다른 전송이 이미 커밋됐을 수 있습니다.
                if (knownDelivery(
                        db,
                        comCd,
                        eventId,
                        payload,
                        digest,
                        source,
                        equipment,
                        type,
                        observed,
                        fileName,
                        fileExtension,
                        itemCode,
                        cmnt)) {
                    db.commit();
                    return "duplicate";
                }
                String status = "accepted";
                if (type.equals("modbus_tcp")) {
                    int applied = 0;
                    for (var reading : readings)
                        if (production(db, comCd, equipmentId, eventId, digest, received, reading))
                            applied++;
                    if (applied == 0) status = "ignored_stale";
                    else if (applied < readings.size()) status = "accepted_partial";
                } else {
                    insertId(
                            db,
                            INSERT_FILE_ROW,
                            comCd,
                            equipmentId,
                            source,
                            type,
                            eventId,
                            databaseTime(observed),
                            received,
                            fileName,
                            fileExtension,
                            itemCode,
                            cmnt,
                            payload);
                }
                db.commit();
                return status;
            } catch (Exception e) {
                db.rollback();
                throw e;
            }
        }
    }

    private static boolean knownDelivery(
            Connection db,
            String comCd,
            String eventId,
            String payload,
            byte[] digest,
            String source,
            String equipment,
            String type,
            Instant observed,
            String fileName,
            String fileExtension,
            String itemCode,
            String cmnt)
            throws SQLException {
        var file =
                one(
                        db,
                        """
                        SELECT d.payload,d.observed_at,d.file_name,d.file_extension,d.item_code,d.cmnt,
                               d.source_code,e.equipment_code,d.collection_type
                        FROM data_json d
                        JOIN equipment e ON e.com_cd=d.com_cd AND e.id=d.equipment_id
                        WHERE d.com_cd=? AND d.event_id=?
                        """,
                        comCd,
                        eventId);
        if (file != null) {
            verifyFileContent(
                    file,
                    payload,
                    source,
                    equipment,
                    type,
                    observed,
                    fileName,
                    fileExtension,
                    itemCode,
                    cmnt);
            return true;
        }
        if (one(
                        db,
                        "SELECT event_id FROM file_import WHERE com_cd=? AND event_id=?",
                        comCd,
                        eventId)
                != null) throw new Conflict("eventId is already used by a complete file import");
        var recent =
                query(
                        db,
                        "SELECT last_event_hash FROM modbus_state WHERE com_cd=? AND"
                                + " last_event_id=?",
                        comCd,
                        eventId);
        for (var state : recent)
            if (!MessageDigest.isEqual(digest, (byte[]) state.get("last_event_hash")))
                throw new Conflict(
                        "Recent eventId already exists with different content for this comCd");
        return !recent.isEmpty();
    }

    private static void verifyFileContent(
            Map<String, Object> file,
            String payload,
            String source,
            String equipment,
            String type,
            Instant observed,
            String fileName,
            String fileExtension,
            String itemCode,
            String cmnt) {
        if (!payload.equals(file.get("payload"))
                || !source.equals(file.get("source_code"))
                || !equipment.equals(file.get("equipment_code"))
                || !type.equals(file.get("collection_type"))
                || !databaseTime(observed).equals(file.get("observed_at"))
                || !Objects.equals(fileName, file.get("file_name"))
                || !Objects.equals(fileExtension, file.get("file_extension"))
                || !Objects.equals(itemCode, file.get("item_code"))
                || !Objects.equals(cmnt, file.get("cmnt")))
            throw new Conflict("eventId already exists with different content for this comCd");
    }

    private boolean production(
            Connection db,
            String comCd,
            long equipmentId,
            String eventId,
            byte[] digest,
            LocalDateTime received,
            ModbusProduction.Reading next)
            throws Exception {
        var state =
                one(
                        db,
                        "SELECT * FROM modbus_state WHERE com_cd=? AND equipment_id=? AND unit_id=?"
                                + " AND function_code=?",
                        comCd,
                        equipmentId,
                        next.unitId(),
                        next.functionCode());
        ModbusProduction.Reading previous = null;
        Long runId = null;
        ProductionCalculator.State previousState = null;
        if (state != null) {
            Instant previousTime =
                    ((LocalDateTime) state.get("observed_at"))
                            .toInstant(ZoneOffset.UTC)
                            .plusNanos(((Number) state.get("observed_nano")).intValue());
            previous =
                    new ModbusProduction.Reading(
                            next.unitId(),
                            next.functionCode(),
                            previousTime,
                            state.get("status") instanceof Number status ? status.intValue() : -1,
                            ((Number) state.get("counter_value")).longValue(),
                            ((Number) state.get("status_address")).intValue(),
                            (String) state.get("counter_word_order"));
            if (state.get("current_run_id") instanceof Number n) runId = n.longValue();
            previousState =
                    new ProductionCalculator.State(
                            previous,
                            ((LocalDateTime) state.get("received_at")).toInstant(ZoneOffset.UTC),
                            runId != null);
        }
        var transition =
                ProductionCalculator.calculate(
                        previousState,
                        next,
                        received.toInstant(ZoneOffset.UTC),
                        Duration.ofSeconds(maxGapSeconds));
        if (!transition.applied()) {
            LOG.warning(
                    "MODBUS_STALE_OBSERVATION com_cd="
                            + comCd
                            + " equipment_id="
                            + equipmentId
                            + " event_id="
                            + eventId
                            + " unit_id="
                            + next.unitId()
                            + " function_code="
                            + next.functionCode()
                            + " observed_at="
                            + next.observed()
                            + " previous_observed_at="
                            + previous.observed()
                            + "; observation ignored; check collector clock if this persists");
            return false;
        }
        if (transition.interruption() != null) {
            if (runId != null)
                update(
                        db,
                        "UPDATE data_modbus SET run_state='interrupted',end_reason=? WHERE com_cd=?"
                                + " AND id=?",
                        transition.interruption(),
                        comCd,
                        runId);
            runId = null;
        }
        LocalDateTime observed = databaseTime(next.observed());
        switch (transition.action()) {
            case START -> {
                runId =
                        insertId(
                                db,
                                """
                                INSERT INTO data_modbus(
                                    com_cd,equipment_id,unit_id,function_code,started_at,
                                    last_observed_at,production_count,run_state)
                                VALUES(?,?,?,?,?,?,?,'running')
                                """,
                                comCd,
                                equipmentId,
                                next.unitId(),
                                next.functionCode(),
                                observed,
                                observed,
                                transition.quantity());
            }
            case INCREMENT ->
                    update(
                            db,
                            """
                            UPDATE data_modbus SET production_count=production_count+?,last_observed_at=?
                            WHERE com_cd=? AND id=?
                            """,
                            transition.quantity(),
                            observed,
                            comCd,
                            runId);
            case COMPLETE -> {
                update(
                        db,
                        """
                        UPDATE data_modbus
                        SET production_count=production_count+?,ended_at=?,last_observed_at=?,
                            run_state='completed',end_reason='stopped'
                        WHERE com_cd=? AND id=?
                        """,
                        transition.quantity(),
                        observed,
                        observed,
                        comCd,
                        runId);
                runId = null;
            }
            case NONE -> {}
            case IGNORE_STALE ->
                    throw new IllegalStateException("Stale transition must return before storage");
        }
        update(
                db,
                """
                INSERT INTO modbus_state(
                    com_cd,equipment_id,unit_id,function_code,status,counter_value,current_run_id,
                    status_address,counter_word_order,last_event_id,last_event_hash,
                    observed_nano,observed_at,received_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                    status=VALUES(status),counter_value=VALUES(counter_value),
                    current_run_id=VALUES(current_run_id),status_address=VALUES(status_address),
                    counter_word_order=VALUES(counter_word_order),last_event_id=VALUES(last_event_id),
                    last_event_hash=VALUES(last_event_hash),observed_nano=VALUES(observed_nano),
                    observed_at=VALUES(observed_at),received_at=VALUES(received_at)
                """,
                comCd,
                equipmentId,
                next.unitId(),
                next.functionCode(),
                next.validStatus() ? next.status() : null,
                next.counter(),
                runId,
                next.statusAddress(),
                next.wordOrder(),
                eventId,
                digest,
                next.observed().getNano(),
                observed,
                received);
        return true;
    }

    private static long equipment(Connection db, String comCd, String code, LocalDateTime created)
            throws SQLException {
        var existing =
                one(
                        db,
                        "SELECT id FROM equipment WHERE com_cd=? AND equipment_code=?",
                        comCd,
                        code);
        if (existing != null) return ((Number) existing.get("id")).longValue();
        return insertId(
                db,
                "INSERT INTO equipment(com_cd,equipment_code,created_at) VALUES(?,?,?) ON DUPLICATE"
                        + " KEY UPDATE id=LAST_INSERT_ID(id)",
                comCd,
                code,
                created);
    }

    public List<Map<String, Object>> listJsonData(
            String comCd, String equipment, String type, long after, int limit) throws Exception {
        return listJsonData(comCd, equipment, null, type, after, limit);
    }

    public List<Map<String, Object>> listJsonData(
            String comCd, String equipment, String source, String type, long after, int limit)
            throws Exception {
        pagination(after, limit);
        String sql =
                """
                SELECT d.id,d.com_cd,d.equipment_id,d.source_code,e.equipment_code,
                       d.collection_type,d.event_id,d.observed_at,d.received_at,
                       d.file_name,d.file_extension,d.item_code,d.cmnt,d.payload
                FROM data_json d
                JOIN equipment e ON e.com_cd=d.com_cd AND e.id=d.equipment_id
                WHERE d.id>?
                """;
        var args = new ArrayList<Object>();
        args.add(after);
        sql += filters(comCd, equipment, source, args, true);
        if (type != null) {
            if (!Set.of("modbus_tcp", "excel", "text").contains(type))
                throw new ValidationException(
                        "collectionType", "collectionType must be excel, text or modbus_tcp");
            sql += " AND d.collection_type=?";
            args.add(type);
        }
        sql += " ORDER BY d.id LIMIT ?";
        args.add(limit);
        try (var db = connect()) {
            var rows = query(db, sql, args.toArray());
            format(rows, "payload");
            return rows;
        }
    }

    public List<Map<String, Object>> listRuns(
            String comCd, String equipment, String source, long after, int limit) throws Exception {
        pagination(after, limit);
        String sql =
                """
                SELECT d.*,e.equipment_code,
                       TIMESTAMPDIFF(SECOND,d.started_at,COALESCE(d.ended_at,d.last_observed_at))
                           AS observed_seconds
                FROM data_modbus d
                JOIN equipment e ON e.com_cd=d.com_cd AND e.id=d.equipment_id
                WHERE d.id>?
                """;
        var args = new ArrayList<Object>();
        args.add(after);
        sql += filters(comCd, equipment, source, args, false);
        sql += " ORDER BY d.id LIMIT ?";
        args.add(limit);
        try (var db = connect()) {
            var rows = query(db, sql, args.toArray());
            format(rows);
            return rows;
        }
    }

    public List<Map<String, Object>> listStatus(
            String comCd, String equipment, String source, long after, int limit) throws Exception {
        pagination(after, limit);
        String sql =
                """
                SELECT d.id,d.com_cd,d.equipment_id,d.unit_id,d.function_code,d.status,
                       d.counter_value,d.current_run_id,d.observed_at,d.received_at,e.equipment_code
                FROM modbus_state d
                JOIN equipment e ON e.com_cd=d.com_cd AND e.id=d.equipment_id
                WHERE d.id>?
                """;
        var args = new ArrayList<Object>();
        args.add(after);
        sql += filters(comCd, equipment, source, args, false);
        sql += " ORDER BY d.id LIMIT ?";
        args.add(limit);
        try (var db = connect()) {
            var rows = query(db, sql, args.toArray());
            LocalDateTime now = databaseTime(clock.instant());
            for (var row : rows) {
                boolean connected =
                        Duration.between((LocalDateTime) row.get("received_at"), now).getSeconds()
                                <= maxGapSeconds;
                row.put("connected", connected);
                row.put(
                        "production_status",
                        !connected
                                ? "disconnected"
                                : row.get("status") == null
                                        ? "unknown"
                                        : ((Number) row.get("status")).intValue() == 1
                                                ? "producing"
                                                : "stopped");
            }
            format(rows);
            return rows;
        }
    }

    private static String filters(
            String comCd, String equipment, String source, List<Object> args, boolean fileData) {
        String sql = "";
        if (comCd != null) {
            Event.id(comCd, "com_cd");
            sql += " AND d.com_cd=?";
            args.add(comCd);
        }
        if (equipment != null) {
            Event.id(equipment, "equipmentId");
            sql += " AND e.equipment_code=?";
            args.add(equipment);
        }
        if (source != null) {
            Event.id(source, "sourceId");
            sql += fileData ? " AND d.source_code=?" : " AND e.equipment_code=?";
            args.add(source);
        }
        return sql;
    }

    private static void pagination(long after, int limit) {
        ValidationException.require(after >= 0, "after", "after must not be negative");
        ValidationException.require(
                limit >= 1 && limit <= 1000, "limit", "limit must be between 1 and 1000");
    }

    private static void format(List<Map<String, Object>> rows, String... jsonColumns)
            throws Exception {
        for (var row : rows) {
            row.replaceAll(
                    (key, value) ->
                            value instanceof LocalDateTime date ? DATE_TIME.format(date) : value);
            for (String column : jsonColumns)
                row.put(column, Json.MAPPER.readTree((String) row.get(column)));
        }
    }

    private static LocalDateTime databaseTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    private static boolean tableExists(Connection db, String table) throws SQLException {
        return one(
                        db,
                        "SELECT TABLE_NAME FROM information_schema.TABLES WHERE"
                                + " TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?",
                        table)
                != null;
    }

    private static boolean columnExists(Connection db, String table, String column)
            throws SQLException {
        return one(
                        db,
                        "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE"
                                + " TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?",
                        table,
                        column)
                != null;
    }

    private static void validateSchema(Connection db) throws SQLException {
        if (!columnExists(db, "data_json", "file_name")
                || !columnExists(db, "data_json", "file_extension")
                || !columnExists(db, "data_json", "item_code")
                || !columnExists(db, "data_json", "cmnt"))
            throw new SQLException(
                    "Update data_json for file_name, item_code, cmnt and named payload entries"
                            + " before starting IMS");
        var dateColumns =
                Map.of(
                        "equipment",
                        List.of("created_at"),
                        "data_json",
                        List.of("observed_at", "received_at"),
                        "data_modbus",
                        List.of("started_at", "ended_at", "last_observed_at"),
                        "modbus_state",
                        List.of("observed_at", "received_at"),
                        "file_import",
                        List.of("received_at"));
        for (var entry : dateColumns.entrySet()) {
            String table = entry.getKey();
            if (!tableExists(db, table))
                throw new SQLException(
                        "Create the equipment schema before starting IMS: missing " + table);
            var storage =
                    one(
                            db,
                            "SELECT ENGINE FROM information_schema.TABLES WHERE"
                                    + " TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?",
                            table);
            if (storage == null || !"InnoDB".equalsIgnoreCase((String) storage.get("engine")))
                throw new SQLException(table + " must use InnoDB for transactional storage");
            var code =
                    one(
                            db,
                            "SELECT DATA_TYPE,IS_NULLABLE FROM information_schema.COLUMNS WHERE"
                                    + " TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND"
                                    + " COLUMN_NAME='com_cd'",
                            table);
            if (code == null
                    || !"varchar".equals(code.get("data_type"))
                    || !"NO".equals(code.get("is_nullable")))
                throw new SQLException(table + ".com_cd must be a required company code");
            if (!table.equals("equipment") && !table.equals("file_import")) {
                var type =
                        one(
                                db,
                                "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE"
                                        + " TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND"
                                        + " COLUMN_NAME='equipment_id'",
                                table);
                if (type == null || !"bigint".equals(type.get("data_type")))
                    throw new SQLException(table + ".equipment_id must reference equipment.id");
            }
            for (String column : entry.getValue()) {
                var type =
                        one(
                                db,
                                "SELECT DATA_TYPE,DATETIME_PRECISION FROM"
                                    + " information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()"
                                    + " AND TABLE_NAME=? AND COLUMN_NAME=?",
                                table,
                                column);
                if (type == null
                        || !"datetime".equals(type.get("data_type"))
                        || !(type.get("datetime_precision") instanceof Number precision)
                        || precision.intValue() != 0)
                    throw new SQLException(
                            "Convert " + table + "." + column + " to DATETIME before starting IMS");
            }
        }
        for (String column : List.of("event_id", "request_hash", "row_count")) {
            if (!columnExists(db, "file_import", column))
                throw new SQLException("Update file_import before starting IMS: missing " + column);
        }
        var receiptHash =
                one(
                        db,
                        """
                        SELECT DATA_TYPE,CHARACTER_MAXIMUM_LENGTH,IS_NULLABLE
                        FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='file_import' AND COLUMN_NAME='request_hash'
                        """);
        if (!"binary".equals(receiptHash.get("data_type"))
                || !"NO".equals(receiptHash.get("is_nullable"))
                || !(receiptHash.get("character_maximum_length") instanceof Number hashSize)
                || hashSize.intValue() != 32)
            throw new SQLException("file_import.request_hash must be required BINARY(32)");
        for (String column :
                List.of(
                        "status_address",
                        "counter_word_order",
                        "last_event_id",
                        "last_event_hash",
                        "observed_nano"))
            if (!columnExists(db, "modbus_state", column))
                throw new SQLException(
                        "Migrate modbus_state before starting IMS: missing " + column);
        for (String fk :
                List.of(
                        "fk_json_equipment",
                        "fk_modbus_equipment",
                        "fk_state_equipment",
                        "fk_state_run"))
            if (one(
                            db,
                            "SELECT CONSTRAINT_NAME FROM information_schema.REFERENTIAL_CONSTRAINTS"
                                    + " WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME=?",
                            fk)
                    == null) throw new SQLException("Missing equipment foreign key: " + fk);
    }

    private static void acquireDeliveryLock(Connection db, String comCd, String eventId)
            throws Exception {
        String identity = db.getCatalog() + "/" + comCd + "/" + eventId;
        String lock =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(identity.getBytes(StandardCharsets.UTF_8)));
        // 실제 DB 연결을 닫으면 전송별로 획득한 명시적 잠금도 해제됩니다.
        var result = one(db, "SELECT GET_LOCK(?,10) AS acquired", lock);
        if (result == null
                || !(result.get("acquired") instanceof Number acquired)
                || acquired.intValue() != 1)
            throw new SQLTimeoutException("Timed out waiting for the delivery lock");
    }

    private static void bind(PreparedStatement s, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            if (params[i] instanceof LocalDateTime date) s.setObject(i + 1, date, Types.TIMESTAMP);
            else s.setObject(i + 1, params[i]);
        }
    }

    private static long insertId(Connection db, String sql, Object... params) throws SQLException {
        try (var s = db.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(s, params);
            s.executeUpdate();
            try (var keys = s.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Generated id missing");
                return keys.getLong(1);
            }
        }
    }

    private static void update(Connection db, String sql, Object... params) throws SQLException {
        try (var s = db.prepareStatement(sql)) {
            bind(s, params);
            s.executeUpdate();
        }
    }

    private static Map<String, Object> one(Connection db, String sql, Object... params)
            throws SQLException {
        var rows = query(db, sql, params);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static List<Map<String, Object>> query(Connection db, String sql, Object... params)
            throws SQLException {
        try (var s = db.prepareStatement(sql)) {
            bind(s, params);
            try (var r = s.executeQuery()) {
                var rows = new ArrayList<Map<String, Object>>();
                var meta = r.getMetaData();
                while (r.next()) {
                    var row = new LinkedHashMap<String, Object>();
                    for (int i = 1; i <= meta.getColumnCount(); i++)
                        row.put(
                                meta.getColumnLabel(i).toLowerCase(Locale.ROOT),
                                meta.getColumnType(i) == Types.TIMESTAMP
                                        ? r.getObject(i, LocalDateTime.class)
                                        : r.getObject(i));
                    rows.add(row);
                }
                return rows;
            }
        }
    }
}

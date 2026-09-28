-- DataBridge Hub: MariaDB 데이터베이스 및 테이블 생성문
-- 애플리케이션 Repository.schema()와 동일한 테이블 구조입니다.
-- 스키마만 생성합니다. 데이터 INSERT 및 기존 데이터 삭제문은 없습니다.
-- 현재 database.url과 맞춰 DB 이름은 ip_multimedia_subsystem을 유지합니다.
-- DB 이름을 바꾸려면 아래 CREATE DATABASE, USE와 application.properties의 database.url을 함께 바꾸세요.
-- 신규 설치용입니다. 이미 존재하는 테이블의 구조를 변경하지 않습니다.
-- 생성 순서: equipment -> data_json -> data_modbus -> modbus_state -> file_import
-- DATETIME은 초 단위 UTC이며, 한국 시각 표시는 조회 화면에서 변환합니다.

CREATE DATABASE IF NOT EXISTS `ip_multimedia_subsystem`
  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

USE `ip_multimedia_subsystem`;

CREATE TABLE IF NOT EXISTS equipment (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '다른 테이블에서 참조하는 설비 번호',
    com_cd VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '회사 코드: 설정의 com_cd',
    equipment_code VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '설정의 equipmentId 또는 nodeId',
    created_at DATETIME NOT NULL COMMENT '최초 수신 시각 (UTC)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_equipment_code (com_cd,equipment_code),
    UNIQUE KEY uq_equipment_tenant_id (com_cd,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='회사별 설비: 같은 설비의 Excel/Text/Modbus가 공유'
;

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
;

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
;

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
;

CREATE TABLE IF NOT EXISTS file_import (
    com_cd VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '회사 코드',
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '파일 전체 전송 UUID',
    request_hash BINARY(32) NOT NULL COMMENT '파일 전송 전체 내용의 SHA-256',
    row_count INT UNSIGNED NOT NULL COMMENT '함께 저장한 데이터 행 수',
    received_at DATETIME NOT NULL COMMENT '파일 전체 저장 완료 시각 (UTC)',
    PRIMARY KEY (com_cd,event_id),
    CONSTRAINT ck_import_row_count CHECK (row_count BETWEEN 1 AND 10000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='파일 전체 저장 확인 및 재전송 내용 비교'
;

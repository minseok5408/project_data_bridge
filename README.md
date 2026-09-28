# project_data_bridge

Modbus TCP 장비와 Excel/Text 파일에서 데이터를 수집해 MariaDB에 저장하는 프로젝트입니다.

고객사 PC에서 `data-collector`를 실행하고, 수집한 데이터는 `data-hub`로 전송합니다. 허브는 데이터를 저장하고 조회 API를 제공합니다.

## 프로젝트 구성

```text
project_data_bridge/
├── data-collector/
│   ├── config/       # 수집 대상과 파일 매핑 설정
│   ├── deploy/       # Windows 서비스 설정
│   ├── scripts/      # 빌드, 실행, 패키징
│   └── src/
├── data-hub/
│   ├── database/     # DB 생성 SQL
│   ├── deploy/       # Linux 서비스 설정
│   ├── scripts/      # 빌드, 실행, DB 테스트
│   └── src/
├── build.ps1
├── pom.xml
└── README.md
```

| 모듈 | 역할 | 빌드 결과 |
|---|---|---|
| data-collector | Modbus, Excel, Text 수집 및 HTTP 전송 | `data-collector/target/data-collector.jar` |
| data-hub | 데이터 수신, MariaDB 저장, 조회 API | `data-hub/target/data-hub.jar` |

Java 25.0.3과 Maven을 사용합니다. Excel 처리는 Apache POI, JSON 처리는 Jackson을 사용하고, 허브는 별도 웹 프레임워크 없이 구현되어 있습니다.

이 문서의 실행 명령은 별도 안내가 없으면 프로젝트 최상위 폴더 기준입니다.

## 로컬 실행

### 1. 설정 파일 준비

실제 접속 정보가 들어가는 `application.properties`는 Git에서 제외되어 있습니다.
처음 받은 경우 예제 파일을 복사해서 사용합니다.

Git Bash에서:

```bash
cp -n data-collector/src/main/resources/application.example.properties data-collector/src/main/resources/application.properties
cp -n data-hub/src/main/resources/application.example.properties data-hub/src/main/resources/application.properties
```

수집기 설정:

```properties
ims.token=replace-with-a-random-shared-token
```

허브 설정:

```properties
server.host=127.0.0.1
server.port=8000

ims.token=replace-with-a-random-shared-token

database.url=jdbc:mariadb://127.0.0.1:3306/ip_multimedia_subsystem
database.username=DB계정
database.password=DB비밀번호
database.initialize-schema=true

modbus.max-gap-seconds=15
```

두 모듈의 `ims.token`은 같은 값으로 맞춰야 합니다.
공백 없는 ASCII 문자 16자 이상을 사용하고, 위 예제는 실제 사용할 임의 값으로 바꿉니다.
설정 파일은 UTF-8로 저장하고, 값에 따옴표를 붙이지 않습니다.

다른 PC의 수집기에서 허브에 접속하려면 `server.host`와 방화벽 설정도 확인해야 합니다. 기본값인 `127.0.0.1`은 같은 PC에서만 접속할 수 있습니다.

### 2. DB 준비

MariaDB에 DB와 테이블을 만들 때는 `data-hub/database/schema.sql`을 사용합니다.

기본 DB 이름은 `ip_multimedia_subsystem`입니다. 다른 이름을 쓰려면 SQL의 DB 이름과 `database.url`을 함께 변경합니다.

DB가 준비되어 있고 `database.initialize-schema=true`이면 허브가 시작할 때 테이블을 생성합니다. DDL 권한이 없는 계정으로 실행할 경우 SQL로 테이블을 먼저 만든 뒤 `false`로 설정합니다.

기존 테이블의 구조 변경은 별도로 처리해야 합니다.
파일 전체 저장에는 `file_import` 테이블이 필요합니다. 자동 생성이 꺼져 있으면 새 SQL의 해당 생성문을 먼저 적용합니다.

### 3. 빌드

PowerShell에서 실행합니다.

DB 없이 두 모듈을 빌드하고 단위 테스트를 실행할 때는 `.\build.ps1`을 사용합니다.
MariaDB 연동 테스트까지 확인할 때는 아래 명령을 사용합니다.

```powershell
.\data-hub\scripts\build.ps1
```

수집기를 먼저 빌드한 다음 허브를 빌드합니다. 허브 통합 테스트는 설치된 MariaDB 실행 파일로 별도 임시 인스턴스를 띄워 진행합니다.

MariaDB 설치 경로를 찾지 못하면 직접 지정합니다.

```powershell
.\data-hub\scripts\build.ps1 -MariaDbHome 'C:\path\to\MariaDB'
```

빌드 스크립트는 필요한 JDK와 Maven을 `data-collector/.tools`에 준비합니다.
설치된 JDK를 사용하려면 `-JdkHome`을 지정하면 됩니다. 현재 프로젝트는 Java 25.0.3을 사용하도록 제한되어 있습니다.

수집기만 빌드할 때는 다음 명령을 사용합니다.

```powershell
.\data-collector\scripts\build.ps1
```

### 4. 실행

허브를 먼저 실행합니다.

```powershell
.\data-hub\scripts\run.ps1
```

다른 터미널에서 수집기를 실행합니다.

```powershell
.\data-collector\scripts\run.ps1
```

Git Bash에서 JAR를 직접 실행할 수도 있습니다. `java -version`으로 Java 25.0.3을 확인하고, 허브와 수집기를 각각 다른 터미널에서 실행합니다.

```bash
# 허브
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar data-hub/target/data-hub.jar run --config data-hub/src/main/resources/application.properties
```

```bash
# 수집기
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar data-collector/target/data-collector.jar run --config data-collector/config/collector.json
```

한글이 깨지면 실행 전에 터미널을 UTF-8로 맞춥니다.

```bash
chcp.com 65001 > /dev/null
```

수집기에서 사용할 수 있는 명령은 다음과 같습니다.

| 명령 | 동작 |
|---|---|
| `run` | 설정한 주기로 수집하고 허브에 전송 |
| `once` | 한 번 수집하고 전송한 뒤 종료 |
| `read` | Modbus 값을 주기적으로 읽어서 출력 |
| `read-once` | Modbus 값을 한 번 읽어서 출력 |
| `validate` | 설정 검사 |
| `status` | 파일 전체 저장 방식과 이전 행별 기록 확인 |

```powershell
.\data-collector\scripts\run.ps1 -Command validate
.\data-collector\scripts\run.ps1 -Command once
.\data-collector\scripts\run.ps1 -Command read-once
```

`read`, `read-once`는 Modbus 설정에서만 사용할 수 있습니다. 허브에 데이터를 보내지 않으므로 장비 연결과 레지스터 값부터 확인할 때 사용하면 됩니다.

`status`의 `atomicFileDelivery`는 파일 전체 저장 사용 여부이고, `legacyRowCheckpoints`는 이전 버전의 행별 기록 수입니다. 현재 파일의 처리 행 번호를 나타내지는 않습니다.

## 수집 설정

공통 설정은 `data-collector/config/collector.json`에 있습니다.

```json
{
  "timezone": "Asia/Seoul",
  "statusAddress": 1,
  "rootDir": "..",
  "stateDir": "runtime",
  "endpoint": "http://127.0.0.1:8000/api/v1/events",
  "sourceFile": "companylaptop_01.json"
}
```

`sourceFile`에 지정한 파일 하나만 읽습니다. `config` 폴더에 JSON 파일을 추가하는 것만으로 수집 대상이 늘어나지는 않습니다.

현재 설정 파일은 두 개입니다.

| 파일 | 용도 |
|---|---|
| `companylaptop_01.json` | Modbus TCP 수집 |
| `companypc_01.json` | Excel/Text 수집 |

수집 대상을 바꿀 때는 `sourceFile`을 변경하고 수집기를 재시작합니다.
설정 파일에서는 `//`, `/* ... */` 주석을 사용할 수 있습니다.

경로와 접속 설정은 다음 기준으로 해석합니다.

- `rootDir`: `collector.json`이 있는 폴더 기준
- `stateDir`, 파일 수집의 `directory`: `rootDir` 기준
- `endpoint`: 데이터를 받을 허브 주소
- `timezone`: 시간대가 없는 파일 측정시각에 적용할 시간대

허브가 다른 PC에 있으면 `endpoint`의 `127.0.0.1`을 실제 허브 주소로 변경해야 합니다.

회사 코드는 각 `nodes` 또는 `sources` 항목의 `com_cd`에 입력합니다.

### Modbus TCP

`companylaptop_01.json`의 설정 예시입니다.

```json
{
  "collectionType": "modbus_tcp",
  "nodes": [
    {
      "nodeId": "companylaptop_01",
      "com_cd": "company",
      "host": "192.168.0.158",
      "port": 502,
      "counterWordOrder": "HIGH_LOW",
      "pollIntervalMs": 2000,
      "timeoutSeconds": 3,
      "enabled": true,
      "readBlocks": [
        {
          "unitId": 1,
          "functionCode": 3,
          "startAddress": 1,
          "registerCount": 3
        }
      ]
    }
  ]
}
```

`host`, `port`, `unitId`는 연결할 장비에 맞춰 변경합니다.
장비가 여러 대면 `nodes`에 추가하고, 한 장비에서 여러 구간을 읽어야 하면 `readBlocks`에 추가합니다.

| 항목 | 설명 |
|---|---|
| `nodeId` | 회사 내 장비 식별자 |
| `pollIntervalMs` | 수집·전송이 끝난 뒤 다음 실행까지 대기 시간 |
| `timeoutSeconds` | Modbus 연결 및 응답 대기 시간 |
| `functionCode` | 3: Holding Register, 4: Input Register |
| `startAddress` | 통신에 사용하는 0부터 시작하는 주소 |
| `registerCount` | 읽을 레지스터 개수, 1~125 |
| `counterWordOrder` | 누적 생산량을 구성하는 두 레지스터의 순서 |

`startAddress`에는 40001 같은 표시 주소를 그대로 넣지 않습니다. 장비 매뉴얼에서 실제 통신 주소를 확인해야 합니다.

현재 생산량 계산은 상태 주소와 그다음 두 레지스터를 사용합니다.

| 주소 | 값 |
|---|---|
| 1 | 생산 상태. 0은 중지, 1은 생산 중 |
| 2 | 누적 생산량 상위 16비트 (`HIGH_LOW` 기준) |
| 3 | 누적 생산량 하위 16비트 (`HIGH_LOW` 기준) |

상태 주소는 공통 설정의 `statusAddress`로 지정합니다.
워드 순서가 반대인 장비는 `counterWordOrder`를 `LOW_HIGH`로 설정합니다.

허브로 전송할 때는 상태와 누적값을 포함하는 3개 레지스터가 필요합니다.
한 장비의 여러 구간을 읽다가 일부 구간에서 실패하면 해당 장비의 부분 데이터는 전송하지 않습니다.

### Excel / Text

`collector.json`의 `sourceFile`을 `companypc_01.json`으로 변경합니다.

현재 설정은 `C:/data`에 있는 TXT와 XLSX 파일을 읽습니다. 두 방식을 함께 사용하므로 `collectionType`은 다음과 같습니다.

```json
{
  "collectionType": ["text", "excel"]
}
```

각 `sources` 항목에는 `type`을 지정합니다.
한 방식만 사용한다면 `collectionType`을 `"text"` 또는 `"excel"`로 지정할 수 있습니다.

현재 샘플 파일의 열 구성은 다음과 같습니다.

```text
측정시각 | 항목코드 | 값1 | 값2 | 값3 | 비고
```

- Text: 탭 구분, UTF-8, 첫 줄은 제목
- Excel: 첫 번째 시트, 첫 행은 제목, 두 번째 행부터 데이터

파일 매핑은 `fields`와 `payload`로 나눕니다.

- `fields`: 측정시각, 항목코드, 비고처럼 별도 DB 컬럼에 저장할 값
- `payload`: JSON으로 저장할 측정값

Excel 측정값 매핑 예시:

```json
{
  "payload": {
    "value1": {
      "type": "float",
      "headerCell": "C1",
      "headerName": "값1"
    },
    "value2": {
      "type": "float",
      "headerCell": "D1",
      "headerName": "값2"
    },
    "value3": {
      "type": "float",
      "headerCell": "E1",
      "headerName": "값3"
    }
  }
}
```

`headerCell`은 제목 셀입니다. `C1`이면 C열을 읽고, 실제 데이터는 `firstDataRow`부터 처리합니다.
Text에서는 `headerCell` 대신 1부터 시작하는 `column` 번호를 사용합니다.

`headerName`은 파일의 제목과 비교하고, DB에 저장할 JSON 키로도 사용합니다. 제목이 다르면 오류 파일로 처리합니다.

```json
{
  "값1": "20",
  "값2": "30",
  "값3": "40"
}
```

값은 설정한 타입으로 검사·변환한 뒤 문자열로 저장합니다.
앞자리 0을 유지해야 하는 코드는 `type: "str"`로 지정합니다.

Excel은 `.xls`, `.xlsx`를 지원하고, 수식은 파일에 저장된 계산 결과를 읽습니다.
Text는 UTF-8 외에 MS949, EUC-KR도 사용할 수 있습니다.
기존 `batchLines` 설정은 호환을 위해 읽지만 파일을 나눠 저장하는 용도로 사용하지 않습니다.

자세한 설정 항목은 `collector.json` 상단 주석을 참고하면 됩니다.

## 데이터 처리

### 파일 처리와 재실행

Excel/Text 파일은 모든 행을 읽고 검증한 뒤 한 요청으로 보냅니다. 허브는 파일 전체를 한 트랜잭션으로 저장합니다. 11번째 행을 저장하다 오류가 나면 앞의 10개 행도 모두 롤백합니다. 이전에 처리한 다른 파일의 데이터는 그대로 유지합니다.
한 파일은 최대 10,000개 데이터 행, 전송할 JSON 본문은 UTF-8 기준 16 MiB까지 지원합니다. 한도를 넘는 파일은 전송하지 않습니다.

파일 처리가 끝나면 입력 폴더 아래로 이동합니다.

```text
C:/data/
├── complete/   # 모든 행의 전송이 끝난 파일
└── error/      # 읽기, 변환, 전송 중 오류가 난 파일
```

오류 파일은 자동으로 재시도하지 않습니다.
최초 전송 전의 읽기·변환 오류는 허브에 요청하지 않고 원본만 `error`에 보관합니다. 파일을 수정한 뒤 입력 폴더로 옮기면 처음부터 다시 처리합니다.
네트워크 오류나 응답 유실은 이미 저장됐을 수도 있으므로 파일과 함께 생성된 `<파일명>.retry.json`을 모두 돌려놓습니다. 같은 파일 ID와 최초 처리 시각으로 파일 전체를 다시 보내며, 이미 저장한 요청은 `duplicate`로 처리합니다. 이때 파일 내용과 매핑을 바꾸면 재전송을 거부하고, 다시 읽기·변환에 실패해도 기존 재시도 정보는 보존합니다.

처리 중인 원본은 입력 폴더의 `.databridge` 아래에서 관리합니다. 같은 이름의 새 파일은 별도 입력으로 구분합니다. 처리 중인 폴더와 `.retry.json`은 직접 수정하지 않습니다.

행별로 저장하던 이전 버전에서 변경할 때는 수집기와 허브를 함께 업데이트합니다. 기존 `progress.json`에 행별 기록이 남아 있거나 구형 미완료 처리 기록이 있으면 시작을 중단합니다. 업데이트 전에 수집기를 멈추고 DB 저장 내역과 미완료 파일을 대조해 재처리 범위를 정해야 합니다. 일부 행이 이미 저장된 파일을 그대로 다시 넣으면 중복될 수 있습니다.

완료 파일이나 재시도 정보가 없는 파일을 다시 넣으면 새 입력으로 처리되어 기존 데이터와 별도로 저장될 수 있습니다. 행 단위 이어 읽기는 사용하지 않습니다.
파일 매핑을 변경할 때는 새로운 `sources.id`를 사용하는 편이 좋습니다.

작성 중인 파일이 수집되지 않도록 다른 폴더에서 저장을 마친 뒤 입력 폴더로 옮겨 넣는 방식을 권장합니다.

수집기에는 별도 전송 대기열이 없습니다. Modbus 전송에 실패한 값은 보관하지 않고 다음 주기에 새로 읽습니다. 파일의 재시도 정보는 원본과 함께 보관하며, 같은 파일 요청의 중복 저장은 허브가 확인합니다.

### DB 구조

| 테이블 | 저장 내용 |
|---|---|
| `equipment` | 회사별 설비 정보 |
| `data_json` | Excel/Text 측정값과 파일 정보 |
| `file_import` | 파일 전체 요청의 중복 확인 기록 |
| `data_modbus` | Modbus 생산 구간과 생산수량 |
| `modbus_state` | 다음 계산에 사용할 최근 상태와 누적값 |

회사는 `com_cd`, 설비는 `(com_cd, equipment_code)`로 구분합니다.
`equipment_code`에는 파일 설정의 `equipmentId` 또는 Modbus의 `nodeId`가 들어갑니다.

같은 회사와 설비 코드를 사용하면 수집 방식이 달라도 하나의 설비로 관리됩니다.

Excel/Text의 측정값은 `data_json.payload`에 저장합니다.
측정시각, 항목코드, 비고, 원본 파일명은 각각 별도 컬럼에 저장합니다.

Modbus는 원시 JSON을 쌓지 않고 생산 구간과 최근 계산 상태를 저장합니다.
DB 시각은 UTC 기준이므로 화면에 한국 시각으로 표시할 때는 `Asia/Seoul`로 변환해야 합니다.

### Modbus 생산수량 계산

두 레지스터를 합쳐 unsigned 32비트 누적값으로 계산합니다.

```text
누적값 = 상위값 × 65536 + 하위값
생산 증가분 = 현재 누적값 - 직전 누적값
```

생산 구간은 아래 기준으로 처리합니다.

- 처음 받은 누적값은 기준값으로 저장합니다.
- 상태가 0에서 1로 바뀌면 새 생산 구간을 시작합니다.
- 생산 중에는 누적값 증가분을 현재 구간에 더합니다.
- 상태가 1에서 0으로 바뀌면 마지막 증가분까지 반영하고 구간을 종료합니다.
- 생산 중 누적값이 줄면 카운터 초기화로 보고 구간을 나눕니다.
- 상태가 0이나 1이 아니면 생산 중지로 확정하지 않습니다.

수신 간격이 `modbus.max-gap-seconds`를 넘으면 다음 수신 시 기존 구간을 `interrupted`로 처리합니다. 기본값은 15초입니다.
중지 시각을 확인하지 못한 구간의 `ended_at`은 비워 둡니다.

통신 상태가 `disconnected`인 것과 설비가 생산을 중지한 것은 따로 구분합니다.

장비는 휴식 중에도 최종 누적값을 유지해야 마지막 생산량을 확인할 수 있습니다. 수집기가 읽기 전에 장비의 누적값이 초기화되면 누락된 수량은 복원할 수 없습니다.

## API

데이터 API는 공유 토큰으로 인증합니다.

```http
Authorization: Bearer <ims.token>
```

| 메서드 | 경로 | 용도 |
|---|---|---|
| GET | `/health` | 서버 및 DB 상태 확인 |
| POST | `/api/v1/data` | 파일 데이터 저장 또는 Modbus 상태 갱신 |
| GET | `/api/v1/data-json` | Excel/Text 이력 조회 |
| GET | `/api/v1/modbus/runs` | 생산 구간과 수량 조회 |
| GET | `/api/v1/modbus/status` | 최근 생산 상태와 통신 상태 조회 |

수집기 기본 전송 주소인 `/api/v1/events`도 지원합니다.

POST 요청에는 `Content-Type: application/json`과 본문의 `eventId`에 맞는 `Idempotency-Key`가 필요합니다.
수집기의 파일 요청은 `{"eventId":"파일 UUID","events":[기존 Event, ...]}` 구조입니다. 헤더에는 바깥쪽 파일 `eventId`를 넣으며 모든 행과 처리 기록이 함께 커밋됩니다.
같은 파일 ID와 같은 내용은 `duplicate`, 내용·행수·시각이 바뀐 요청은 HTTP 409입니다. 기존 단일 행 요청과 Modbus 요청도 유지합니다.

단일 행 요청은 같은 회사와 `eventId`로 다시 들어왔을 때 내용을 비교합니다.
같은 내용이면 `duplicate`, 내용이 다르면 HTTP 409를 반환합니다.
같은 파일 행이라도 새로운 `eventId`로 보내면 별도 데이터로 저장될 수 있습니다.

Modbus는 최근 전송의 중복 여부와 관측 시각을 확인해 과거 데이터가 생산수량에 다시 반영되지 않도록 처리합니다.

오래된 값은 `ignored_stale`, 일부만 반영한 경우는 `accepted_partial`로 응답합니다. 허브보다 60초 넘게 앞선 관측 시각은 거부하므로 수집기 PC의 시계도 맞춰야 합니다.

조회 시 `com_cd`, `equipmentId`, `sourceId`로 필터링할 수 있고, 파일 데이터는 `collectionType`도 지원합니다.
`equipmentId`에는 DB 내부 번호가 아닌 설정 파일의 설비 코드를 넣습니다.

목록은 `limit`과 `after`로 페이지를 이동합니다. `limit`은 1~1000이며, `after`에는 마지막으로 받은 id를 넣습니다.
진행 중인 생산 구간은 같은 행의 수량이 갱신되므로 모니터링할 때 해당 구간을 다시 조회해야 합니다.
`after`는 목록 페이지를 넘기는 기준입니다. ID 발급 순서와 커밋 순서가 다를 수 있어, 모든 변경을 빠짐없이 받는 용도로는 보장되지 않습니다.

현재 인증은 공유 토큰 방식이며, 업체별 사용자 권한과 모니터링 화면은 구현되어 있지 않습니다.

## 배포

### 수집기 Windows 배포

```powershell
.\data-collector\scripts\package-windows.ps1
```

결과물은 `data-collector/dist/DataBridgeCollector`에 생성됩니다.
Java 런타임과 설정 파일이 함께 들어 있으므로 폴더 전체를 복사해서 배포합니다.

배포 환경의 수집 설정과 토큰은 `config` 폴더에서 변경합니다.
로그와 실행 잠금은 `state`에 저장합니다. 파일 처리와 재시도 정보는 입력 폴더의 `.databridge` 및 오류 파일 옆에서 관리합니다.

기존 배포 폴더가 있으면 패키징이 중단되므로 기존 결과물을 다른 위치에 보관한 뒤 실행합니다.

서비스 등록은 배포 폴더에서 관리자 PowerShell로 진행합니다.

```powershell
.\service.ps1 install
.\service.ps1 start
.\service.ps1 status
```

중지하거나 서비스를 제거할 때:

```powershell
.\service.ps1 stop
.\service.ps1 uninstall
```

기본 서비스 계정은 LocalSystem입니다. 네트워크 공유 폴더를 읽어야 한다면 해당 경로에 접근할 수 있는 계정으로 변경해야 합니다.

### 허브 배포

JAR와 외부 설정 파일을 배포한 뒤 실행합니다.

```bash
java -jar data-hub.jar run --config config/application.properties
```

Linux 서비스 등록용 파일은 `data-hub/deploy/databridge-hub.service`에 있습니다. 설치 경로와 실행 계정을 맞춰 사용하면 됩니다.

외부 네트워크에서 접속할 경우 HTTPS 리버스 프록시를 구성하고 수집기의 `endpoint`를 해당 주소로 변경합니다.

## 개발 시 참고

- 수집기와 허브에 각각 `Event`, `ModbusData`, `Json` 클래스가 있습니다. 전송 형식을 바꿀 때는 양쪽을 같이 확인해야 합니다.
- 수집기의 `src/main/resources/application.properties`는 빌드 결과에 포함됩니다. 이 파일의 토큰을 수정했다면 다시 빌드해야 합니다.
- 배포 수집기는 `collector.json` 옆의 `application.properties`를 우선 사용합니다. 외부 설정이 잘못된 경우에는 시작을 중단합니다.
- 루트에서 `mvn clean verify`를 실행하면 두 모듈을 함께 빌드하고 DB 없는 단위 테스트를 실행합니다. 수집기를 먼저 `install`할 필요는 없습니다.
- DB 통합 테스트는 `mvn -Pintegration-tests clean verify`로 실행합니다. 테스트 DB 설정이 없으면 실패합니다. 임시 DB까지 준비하려면 `data-hub/scripts/build.ps1`을 사용합니다.

Maven으로 통합 테스트를 직접 실행할 때는 `IMS_TEST_DB_URL`, `IMS_TEST_DB_USER`, `IMS_TEST_DB_PASSWORD`를 지정합니다. URL은 DB 이름 없이 `/`로 끝나야 하며, 계정에는 테스트 DB 생성·삭제 권한이 필요합니다.
임시 DB 스크립트는 성공하면 프로세스와 파일을 정리하고, 실패하면 `data-hub/.tools/test-db-*`에 로그와 DB 파일을 남깁니다.

Windows 배포본까지 확인할 때는 아래 테스트를 실행합니다.

```powershell
.\data-collector\scripts\test-package.ps1
```

별도 임시 MariaDB를 사용해 수집기 EXE에서 허브로 전송한 데이터가 저장되는지 확인합니다. 11번째 행에 오류가 있으면 해당 파일은 저장되지 않고, 오류를 고친 뒤에는 전체 행이 한 번만 저장되는지도 검사합니다.

수집기 로그는 개발 환경에서 `data-collector/runtime/collector-0.log`, 배포 환경에서는 `state`에 남습니다.
Modbus 연결 문제는 `MODBUS_READ_FAILED`, 허브 전송 문제는 `IMS_SEND_FAILED`, 파일 처리 문제는 `FILE_ERROR`부터 확인하면 됩니다.

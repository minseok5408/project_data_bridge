package io.databridge.collector;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.time.ZoneId;
import java.util.*;

public final class CollectorConfig {
    public String timezone = "Asia/Seoul";
    public String rootDir = "..";
    public String stateDir = "runtime";
    public String endpoint = "http://127.0.0.1:8000/api/v1/events";
    public int timeoutSeconds = 10;
    public Integer statusAddress = 1;
    public String sourceFile;

    @com.fasterxml.jackson.annotation.JsonFormat(
            with = com.fasterxml.jackson.annotation.JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    public List<String> collectionType;

    public List<Node> nodes = new ArrayList<>();
    public List<Source> sources = new ArrayList<>();

    public static final class Node {
        public String nodeId;

        @com.fasterxml.jackson.annotation.JsonProperty("com_cd")
        public String comCd;

        public String host;
        public String counterWordOrder = "HIGH_LOW";
        public int port = 502;
        public int pollIntervalMs = 2000;
        public int timeoutSeconds = 3;
        public boolean enabled = true;
        public List<ReadBlock> readBlocks = new ArrayList<>();
    }

    public static final class ReadBlock {
        public int unitId = 1;
        public int functionCode = 3;
        public int startAddress = 0;
        public int registerCount;
    }

    public static final class Source {
        public String id;

        @com.fasterxml.jackson.annotation.JsonProperty("com_cd")
        public String comCd;

        public String type;
        public String equipmentId;
        public boolean enabled = true;
        public int pollIntervalSeconds = 5;
        public String directory;
        public String glob;
        public int settleSeconds = 2;
        public int maxFileMb = 100;
        public String sheet = "0";
        public String layout = "rows";
        public int firstDataRow = 2;
        public String encoding = "UTF-8";
        public String delimiter = "\t";
        public String pattern;
        public int skipLines = 1;
        public String commentPrefix;
        public int batchLines = 1000;

        /** DB의 시간·항목코드·비고 컬럼에 매핑할 항목입니다. */
        public Map<String, Field> fields = new LinkedHashMap<>();

        /** DB payload에 저장할 항목. headerName이 JSON 키가 되고, 생략하면 설정 이름을 씁니다. */
        public Map<String, Field> payload = new LinkedHashMap<>();

        public Map<String, Field> allFields() {
            require(fields != null && payload != null, "fields and payload must be objects");
            var all = new LinkedHashMap<>(fields);
            payload.forEach(
                    (name, field) -> {
                        require(
                                !all.containsKey(name),
                                "Duplicate field name in fields/payload: " + name);
                        all.put(name, field);
                    });
            return all;
        }

        public String target(String name) {
            return payload.containsKey(name) ? "payload" : fields.get(name).target;
        }

        public Map<String, Object> context = new LinkedHashMap<>();
    }

    public static final class Field {
        public Integer column;
        public String headerCell;
        public String headerName;
        public String cell;
        public String group;

        public String type = "float";
        public String target = "measurement";
        public String kind = "sensor";
        public String unit;
        public boolean required = true;
        public java.math.BigDecimal scale = java.math.BigDecimal.ONE;
        public java.math.BigDecimal offset = java.math.BigDecimal.ZERO;
        public Integer decimals;
        public String datetimeFormat;
    }

    public record Loaded(CollectorConfig config, Path root) {}

    public static String exportCommonSettings(Path file) throws Exception {
        load(file);
        return Json.write(readSettings(file.toAbsolutePath().normalize()));
    }

    public static Loaded load(Path file) throws Exception {
        Path defaultsFile = file.toAbsolutePath().normalize();
        var defaults = readSettings(defaultsFile);
        require(
                !defaults.has("sources")
                        && !defaults.has("nodes")
                        && !defaults.has("collectionType"),
                defaultsFile
                        + ": move sources/nodes and collectionType into the selected source file");
        final String sourceFile;
        try {
            sourceFile = Json.MAPPER.treeToValue(defaults, CollectorConfig.class).sourceFile;
            require(sourceFile != null && !sourceFile.isBlank(), "sourceFile is required");
            require(
                    !sourceFile.contains("/")
                            && !sourceFile.contains("\\")
                            && sourceFile.toLowerCase(Locale.ROOT).endsWith(".json")
                            && !Path.of(sourceFile).isAbsolute(),
                    "sourceFile must be a JSON filename inside the config folder");
        } catch (Exception e) {
            throw new IllegalArgumentException(defaultsFile + ": " + e.getMessage(), e);
        }
        Path selectedFile = defaultsFile.resolveSibling(sourceFile);
        require(
                !defaultsFile.equals(selectedFile),
                defaultsFile + ": sourceFile must name a different file");
        var selected = readSettings(selectedFile);
        require(
                !selected.has("sourceFile"),
                selectedFile + ": sourceFile belongs only in " + defaultsFile.getFileName());
        require(
                !selected.has("statusAddress"),
                selectedFile + ": statusAddress belongs only in " + defaultsFile.getFileName());
        var typeValue = selected.path("collectionType");
        var types = new ArrayList<String>();
        if (typeValue.isTextual()) types.add(typeValue.asText());
        else if (typeValue.isArray())
            for (var value : typeValue) {
                require(
                        value.isTextual(),
                        selectedFile + ": collectionType entries must be strings");
                types.add(value.asText());
            }
        require(
                !types.isEmpty()
                        && types.stream().allMatch(Set.of("modbus_tcp", "excel", "text")::contains)
                        && new HashSet<>(types).size() == types.size(),
                selectedFile + ": collectionType requires supported, non-duplicate methods");
        if (types.contains("modbus_tcp")) {
            require(
                    types.size() == 1 && selected.has("nodes") && !selected.has("sources"),
                    selectedFile + ": modbus_tcp uses nodes only");
        } else {
            require(
                    selected.has("sources") && !selected.has("nodes"),
                    selectedFile + ": Excel/Text use sources only");
        }
        // 지정한 파일만 읽고, 같은 폴더의 다른 파일은 탐색하지 않습니다.
        defaults.setAll(selected);
        try {
            var config = Json.MAPPER.treeToValue(defaults, CollectorConfig.class);
            config.validate();
            return new Loaded(config, defaultsFile.getParent().resolve(config.rootDir).normalize());
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    selectedFile + " (defaults: " + defaultsFile + "): " + e.getMessage(), e);
        }
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode readSettings(Path file)
            throws Exception {
        try {
            var value =
                    Json.MAPPER
                            .readerFor(com.fasterxml.jackson.databind.JsonNode.class)
                            .with(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS)
                            .with(
                                    com.fasterxml.jackson.databind.DeserializationFeature
                                            .FAIL_ON_TRAILING_TOKENS)
                            .readTree(Files.readString(file));
            require(
                    value instanceof com.fasterxml.jackson.databind.node.ObjectNode,
                    "JSON object required");
            return (com.fasterxml.jackson.databind.node.ObjectNode) value;
        } catch (Exception e) {
            throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
        }
    }

    public void validate() {
        ZoneId.of(timezone);
        require(
                statusAddress != null && statusAddress >= 0 && statusAddress <= 65535,
                "statusAddress must be 0..65535");
        var uri = URI.create(endpoint);
        require(
                Set.of("http", "https").contains(uri.getScheme())
                        && uri.getHost() != null
                        && uri.getUserInfo() == null
                        && uri.getQuery() == null
                        && uri.getFragment() == null,
                "Invalid HTTP endpoint");
        require(timeoutSeconds > 0 && timeoutSeconds <= 120, "Invalid timeout");
        require(
                nodes != null && sources != null && (!nodes.isEmpty() || !sources.isEmpty()),
                "At least one node or source required");
        if (collectionType != null) {
            require(
                    !collectionType.isEmpty()
                            && collectionType.stream()
                                    .allMatch(
                                            t ->
                                                    t != null
                                                            && Set.of("modbus_tcp", "excel", "text")
                                                                    .contains(t))
                            && new HashSet<>(collectionType).size() == collectionType.size(),
                    "Invalid collectionType");
            if (collectionType.contains("modbus_tcp"))
                require(
                        collectionType.size() == 1 && sources.isEmpty(),
                        "modbus_tcp cannot include Excel/Text sources");
            else {
                require(nodes.isEmpty(), "Excel/Text cannot include Modbus nodes");
                for (var source : sources) {
                    require(source != null, "Null source");
                    if (source.type == null && collectionType.size() == 1)
                        source.type = collectionType.getFirst();
                    require(
                            source.type != null && collectionType.contains(source.type),
                            "Each source.type must be explicitly selected in collectionType: "
                                    + collectionType);
                }
            }
        }
        require(nodes.size() <= 128, "At most 128 Modbus nodes supported");
        Set<String> ids = new HashSet<>();
        for (var node : nodes) {
            require(node != null, "Null Modbus node");
            Event.id(node.nodeId);
            require(
                    node.counterWordOrder != null
                            && Set.of("HIGH_LOW", "LOW_HIGH").contains(node.counterWordOrder),
                    "counterWordOrder must be HIGH_LOW or LOW_HIGH");
            require(
                    node.comCd != null && !node.comCd.isBlank(),
                    "com_cd is required for node " + node.nodeId);
            Event.id(node.comCd);
            require(ids.add(node.comCd + "/" + node.nodeId), "Duplicate node/source id");
            require(
                    node.host != null
                            && !node.host.isBlank()
                            && node.port > 0
                            && node.port <= 65535,
                    "Invalid Modbus ip/port");
            require(
                    node.pollIntervalMs > 0
                            && node.timeoutSeconds > 0
                            && node.timeoutSeconds <= 120,
                    "Invalid Modbus interval/timeout");
            require(
                    node.readBlocks != null
                            && !node.readBlocks.isEmpty()
                            && node.readBlocks.size() <= 64,
                    "1..64 Modbus readBlocks required");
            Set<String> blocks = new HashSet<>();
            for (var readBlock : node.readBlocks) {
                require(readBlock != null, "Null Modbus readBlock");
                require(
                        readBlock.unitId >= 0
                                && readBlock.unitId <= 247
                                && (readBlock.functionCode == 3 || readBlock.functionCode == 4),
                        "Only Modbus FC03/FC04 supported");
                require(
                        readBlock.registerCount >= 1
                                && readBlock.registerCount <= 125
                                && readBlock.startAddress >= 0
                                && readBlock.startAddress <= 65536 - readBlock.registerCount,
                        "Invalid register block");
                require(
                        blocks.add(
                                readBlock.unitId
                                        + "/"
                                        + readBlock.functionCode
                                        + "/"
                                        + readBlock.startAddress
                                        + "/"
                                        + readBlock.registerCount),
                        "Duplicate Modbus readBlock");
            }
        }
        for (var s : sources) {
            Event.id(s.id);
            Event.id(s.equipmentId);
            require(s.comCd != null && !s.comCd.isBlank(), "com_cd is required for source " + s.id);
            Event.id(s.comCd);
            require(ids.add(s.comCd + "/" + s.id), "Duplicate source id");
            require(
                    Set.of("excel", "text").contains(s.type),
                    "sources support excel/text; configure Modbus TCP in nodes");
            require(s.pollIntervalSeconds > 0, "Invalid source interval");
            {
                require(
                        s.directory != null
                                && s.glob != null
                                && !s.glob.contains("/")
                                && !s.glob.contains("\\"),
                        "directory and filename glob required");
                FileSystems.getDefault().getPathMatcher("glob:" + s.glob);
                require(
                        s.maxFileMb > 0
                                && s.maxFileMb <= 1024
                                && s.settleSeconds >= 0
                                && s.firstDataRow > 0,
                        "Invalid file limits");
                require(Set.of("rows", "cells").contains(s.layout), "Invalid Excel layout");
                require(
                        s.batchLines > 0 && s.batchLines <= 100000 && s.skipLines >= 0,
                        "Invalid text limits");
                var charset = Charset.forName(s.encoding).name();
                require(
                        Set.of("UTF-8", "x-windows-949", "EUC-KR", "US-ASCII", "ISO-8859-1")
                                .contains(charset),
                        "Use UTF-8, MS949 or EUC-KR text");
                require(s.delimiter != null && !s.delimiter.isEmpty(), "Empty delimiter");
                if (s.pattern != null) java.util.regex.Pattern.compile(s.pattern);
            }
            var allFields = s.allFields();
            require(!allFields.isEmpty() && allFields.size() <= 512, "1..512 fields required");
            int observedFields = 0, itemFields = 0, commentFields = 0, measurements = 0;
            var payloadKeys = new HashSet<String>();
            for (var entry : allFields.entrySet()) {
                Event.id(entry.getKey());
                var f = entry.getValue();
                require(f != null, "Null field");
                String target = s.target(entry.getKey());
                if (s.payload.containsKey(entry.getKey()))
                    require(
                            Set.of("measurement", "payload").contains(f.target),
                            "Fields in payload cannot target another column");
                if (f.headerName != null)
                    require(!f.headerName.isBlank(), "headerName must not be blank");
                if (f.headerCell != null) {
                    require(
                            s.type.equals("excel") && s.layout.equals("rows"),
                            "headerCell requires Excel rows");
                    f.headerCell = f.headerCell.toUpperCase(Locale.ROOT);
                    require(
                            f.headerCell.matches("[A-Z]{1,3}[1-9][0-9]{0,6}"),
                            "Invalid headerCell");
                    var header = new org.apache.poi.ss.util.CellReference(f.headerCell);
                    require(
                            header.getCol() < 16384
                                    && header.getRow() < 1048576
                                    && header.getRow() + 1 < s.firstDataRow,
                            "headerCell must be above firstDataRow and inside Excel limits");
                    require(
                            f.column == null || f.column == header.getCol() + 1,
                            "column conflicts with headerCell");
                    f.column = header.getCol() + 1;
                }
                if (f.headerName != null && s.type.equals("text") && s.pattern == null)
                    require(s.skipLines > 0, "Text headerName requires skipLines");
                if (f.headerName != null && s.type.equals("excel") && s.layout.equals("rows"))
                    require(s.firstDataRow > 1, "Excel headerName requires a header row");
                require(
                        Set.of("float", "int", "str", "bool", "datetime").contains(f.type),
                        "Invalid field type");
                require(
                        Set.of(
                                        "measurement",
                                        "payload",
                                        "context",
                                        "observed_at",
                                        "item_code",
                                        "cmnt",
                                        "ignore")
                                .contains(target),
                        "Invalid field target");
                require(
                        Set.of("sensor", "counter", "status", "text").contains(f.kind),
                        "Invalid measurement kind");
                require(
                        f.scale != null
                                && f.offset != null
                                && (f.decimals == null || f.decimals >= 0 && f.decimals <= 12),
                        "Invalid numeric transformation");
                if (target.equals("observed_at")) {
                    observedFields++;
                    require(
                            f.required && f.type.equals("datetime"),
                            "observed_at must be a required datetime");
                }
                if (target.equals("item_code")) {
                    itemFields++;
                    require(f.type.equals("str"), "item_code must use type str");
                }
                if (target.equals("cmnt")) {
                    commentFields++;
                    require(f.type.equals("str"), "cmnt must use type str");
                }
                if (Set.of("measurement", "payload").contains(target)) {
                    measurements++;
                    String key = f.headerName == null ? entry.getKey() : f.headerName.strip();
                    require(
                            key.length() <= 255 && payloadKeys.add(key),
                            "Duplicate or too long payload key: " + key);
                    if (target.equals("measurement"))
                        require(
                                switch (f.kind) {
                                    case "status" -> f.type.equals("bool");
                                    case "text" -> f.type.equals("str");
                                    default -> Set.of("float", "int").contains(f.type);
                                },
                                "Measurement kind/type mismatch");
                }
                if (s.type.equals("excel") && s.layout.equals("cells")) {
                    require(
                            f.cell != null && f.cell.matches("[A-Z]+[1-9][0-9]*"),
                            "Excel cell address required");
                } else if (s.type.equals("text") && s.pattern != null) {
                    require(
                            f.group != null && s.pattern.contains("(?<" + f.group + ">"),
                            "Named regex group required");
                } else require(f.column != null && f.column > 0, "Column is 1-based");
            }
            require(
                    observedFields <= 1
                            && itemFields <= 1
                            && commentFields <= 1
                            && measurements > 0
                            && measurements <= 256,
                    "Require 1..256 payload fields and at most one observed_at, item_code and cmnt"
                            + " field");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}

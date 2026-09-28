package io.databridge.collector;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.*;
import java.util.Map;

class CollectorConfigTest {
    @TempDir Path folder;
    private static final String SOURCE =
            """
            {"id":"text_01","com_cd":"company","type":"text","equipmentId":"EQ01","directory":"data/text",
             "glob":"*.txt","fields":{"temperature":{"column":1}}}
            """;

    private Path defaults(String sourceFile) throws Exception {
        Path file = folder.resolve("collector.json");
        Files.writeString(
                file,
                Json.write(Map.of("sourceFile", sourceFile, "timezone", "UTC", "rootDir", "..")));
        return file;
    }

    private void profile(String name, String source) throws Exception {
        Files.writeString(
                folder.resolve(name), "{\"collectionType\":\"text\",\"sources\":[" + source + "]}");
    }

    @Test
    void headerCellDerivesTheColumnAndAcceptsLowercase() throws Exception {
        Path file = defaults("excel.json");
        Files.writeString(
                folder.resolve("excel.json"),
                """
                {"collectionType":"excel","sources":[{"id":"input","com_cd":"company","equipmentId":"EQ01","directory":".","glob":"*.xlsx","fields":{
                  "time":{"headerCell":"c1","headerName":"측정시각","type":"datetime","target":"observed_at"},
                  "quantity":{"headerCell":"B1","headerName":"수량","type":"int","kind":"counter"}
                }}]}
                """);
        var config = CollectorConfig.load(file).config();
        assertEquals(3, config.sources.getFirst().fields.get("time").column);
        assertEquals("C1", config.sources.getFirst().fields.get("time").headerCell);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"headerCell\":\"A0\"",
                "\"headerCell\":\"A2\"",
                "\"headerCell\":\"XFE1\"",
                "\"headerCell\":\"A1\",\"column\":2",
                "\"headerCell\":\"A1\",\"headerName\":\" \""
            })
    void invalidHeaderMappingsAreRejected(String field) throws Exception {
        Path file = defaults("excel.json");
        Files.writeString(
                folder.resolve("excel.json"),
                "{\"collectionType\":\"excel\",\"sources\":[{\"id\":\"input\",\"com_cd\":\"company\",\"equipmentId\":\"EQ01\",\"directory\":\".\",\"glob\":\"*.xlsx\",\"fields\":{\"value\":{"
                        + field
                        + "}}}]}");
        assertThrows(IllegalArgumentException.class, () -> CollectorConfig.load(file));
    }

    @Test
    void readsOnlySelectedFileAndAppliesDefaultsAndOverrides() throws Exception {
        Path file = defaults("machine01.json");
        Files.writeString(
                folder.resolve("machine01.json"),
                "{\"collectionType\":\"text\",\"sources\":[" + SOURCE + "]}");
        Files.writeString(folder.resolve("broken.json"), "This file must never be read");
        profile("another.json", SOURCE.replace("text_01", "other"));
        var loaded = CollectorConfig.load(file);
        assertEquals("company", loaded.config().sources.getFirst().comCd);
        assertEquals("UTC", loaded.config().timezone);
        assertEquals("http://127.0.0.1:8000/api/v1/events", loaded.config().endpoint);
        assertEquals(1, loaded.config().sources.size());
        assertEquals("text_01", loaded.config().sources.getFirst().id);
        assertEquals(folder.toAbsolutePath().getParent(), loaded.root());
        assertEquals(
                0,
                CollectorApplication.execute(
                        new String[] {"validate", "--config", file.toString()}));
    }

    @Test
    void minimalModbusProfileUsesDefaultsWithoutFields() throws Exception {
        Path file = defaults("modbus.json");
        Files.writeString(
                folder.resolve("modbus.json"),
                """
                {"collectionType":"modbus_tcp","nodes":[{"nodeId":"ND01","com_cd":"company","host":"192.168.0.158","readBlocks":[{"startAddress":1,"registerCount":10}]}]}
                """);
        var config = CollectorConfig.load(file).config();
        assertTrue(config.sources.isEmpty());
        assertEquals(1, config.nodes.size());
        var node = config.nodes.getFirst();
        var readBlock = node.readBlocks.getFirst();
        assertEquals(502, node.port);
        assertEquals(2000, node.pollIntervalMs);
        assertEquals(1, readBlock.unitId);
        assertEquals(3, readBlock.functionCode);
        assertEquals(1, readBlock.startAddress);
        assertEquals(10, readBlock.registerCount);
        assertEquals(
                0,
                CollectorApplication.execute(
                        new String[] {"validate", "--config", file.toString()}));
    }

    @ParameterizedTest
    @ValueSource(strings = {"excel", "text"})
    void fileSourcesInheritTheirSelectedCollectionType(String type) throws Exception {
        Path file = defaults("file.json");
        String source = SOURCE.replace("\"type\":\"text\",", "");
        Files.writeString(
                folder.resolve("file.json"),
                "{\"collectionType\":\"" + type + "\",\"sources\":[" + source + "]}");
        assertEquals(type, CollectorConfig.load(file).config().sources.getFirst().type);
    }

    @Test
    void exportConfigValidatesProfileAndPrintsOnlyStrictCommonJsonWithoutAuthentication()
            throws Exception {
        Path file = defaults("source.json");
        Files.writeString(
                file,
                """
                // 배포 설정의 선행 주석도 허용합니다.
                {"sourceFile":"source.json", /* 중간 주석 */ "rootDir":"..", "stateDir":"runtime"}
                """);
        profile("source.json", SOURCE);
        Files.writeString(folder.resolve("application.properties"), "ims.token=invalid\n");
        var output = new java.io.ByteArrayOutputStream();
        var previous = System.out;
        try (var captured =
                new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setOut(captured);
            assertEquals(
                    0,
                    CollectorApplication.execute(
                            new String[] {"export-config", "--config", file.toString()}));
        } finally {
            System.setOut(previous);
        }
        var exported =
                Json.MAPPER
                        .reader()
                        .with(
                                com.fasterxml.jackson.databind.DeserializationFeature
                                        .FAIL_ON_TRAILING_TOKENS)
                        .readTree(output.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("source.json", exported.path("sourceFile").asText());
        assertEquals("runtime", exported.path("stateDir").asText());
        assertFalse(exported.has("sources"));
        assertFalse(exported.has("collectionType"));
        Files.writeString(
                folder.resolve("source.json"), "{\"collectionType\":\"text\",\"sources\":[]}");
        assertThrows(
                IllegalArgumentException.class, () -> CollectorConfig.exportCommonSettings(file));
    }

    @Test
    void commentsAreAllowedInConfigurationFiles() throws Exception {
        Path file = defaults("commented.json");
        Files.writeString(
                folder.resolve("commented.json"),
                """
                /* collectionType으로 지원하는 세 가지 수집 방식 중 하나를 선택합니다. */
                {
                  "collectionType": "modbus_tcp", // 레지스터 값을 변환하지 않고 그대로 읽습니다.
                  "nodes": [{"nodeId":"ND01","com_cd":"company","host":"localhost","readBlocks":[{"registerCount":10}]}]
                }
                """);
        assertEquals(
                10,
                CollectorConfig.load(file)
                        .config()
                        .nodes
                        .getFirst()
                        .readBlocks
                        .getFirst()
                        .registerCount);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"nodes\":[]}",
                "{\"collectionType\":\"serial\",\"nodes\":[]}",
                "{\"collectionType\":\"modbus_tcp\",\"sources\":[]}",
                "{\"collectionType\":\"excel\",\"nodes\":[]}",
                "{\"collectionType\":\"text\",\"sources\":[],\"nodes\":[]}",
                "{\"collectionType\":\"text\",\"sources\":[{\"type\":\"excel\"}]}"
            })
    void wrongOrMixedCollectionTypesFailBeforeCollection(String contents) throws Exception {
        Path file = defaults("wrong-type.json");
        Files.writeString(folder.resolve("wrong-type.json"), contents);
        var error = assertThrows(IllegalArgumentException.class, () -> CollectorConfig.load(file));
        assertTrue(error.getMessage().contains("wrong-type.json"));
    }

    @Test
    void changingOnlySourceFileSelectsAnotherProfile() throws Exception {
        profile("first.json", SOURCE);
        profile("second.json", SOURCE.replace("text_01", "text_02"));
        assertEquals(
                "text_01",
                CollectorConfig.load(defaults("first.json")).config().sources.getFirst().id);
        var config = CollectorConfig.load(defaults("second.json")).config();
        assertEquals(1, config.sources.size());
        assertEquals("text_02", config.sources.getFirst().id);
    }

    @Test
    void missingSelectedFileReportsItsNameWithoutFallback() throws Exception {
        profile("available.json", SOURCE);
        Path file = defaults("missing.json");
        var error = assertThrows(IllegalArgumentException.class, () -> CollectorConfig.load(file));
        assertTrue(error.getMessage().contains("missing.json"));
    }

    @Test
    void selectionMustBePresentInCommonConfig() throws Exception {
        Path file = folder.resolve("collector.json");
        Files.writeString(file, "{}");
        profile("available.json", SOURCE);
        var error = assertThrows(IllegalArgumentException.class, () -> CollectorConfig.load(file));
        assertTrue(error.getMessage().contains("collector.json"));
        assertTrue(error.getMessage().contains("sourceFile is required"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "../outside.json",
                "sub/file.json",
                "sub\\file.json",
                "collector.json",
                "file.txt",
                ""
            })
    void selectionMustNameAnotherJsonInSameFolder(String selected) throws Exception {
        Path file = defaults(selected);
        assertThrows(IllegalArgumentException.class, () -> CollectorConfig.load(file));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{broken",
                "null",
                "[]",
                "{\"sources\":[]}",
                "{\"sources\":null}",
                "{\"sources\":[],\"typo\":1}",
                "{\"sourceFile\":\"another.json\",\"sources\":[]}"
            })
    void invalidSelectedSettingsReportFilename(String contents) throws Exception {
        Path file = defaults("invalid.json");
        Files.writeString(folder.resolve("invalid.json"), contents);
        var error = assertThrows(IllegalArgumentException.class, () -> CollectorConfig.load(file));
        assertTrue(error.getMessage().contains("invalid.json"));
    }

    @Test
    void duplicateSourceIdsAreRejectedWithinSelectedFile() throws Exception {
        Path file = defaults("duplicate.json");
        profile("duplicate.json", SOURCE + "," + SOURCE);
        var error = assertThrows(IllegalArgumentException.class, () -> CollectorConfig.load(file));
        assertTrue(error.getMessage().contains("duplicate.json"));
        assertTrue(error.getMessage().contains("Duplicate source id"));
    }

    @Test
    void sourcesBelongInSelectedFileOnly() throws Exception {
        Path file = defaults("machine.json");
        profile("machine.json", SOURCE);
        Files.writeString(file, "{\"sourceFile\":\"machine.json\",\"sources\":[" + SOURCE + "]}");
        var error = assertThrows(IllegalArgumentException.class, () -> CollectorConfig.load(file));
        assertTrue(error.getMessage().contains("collector.json"));
        assertTrue(error.getMessage().contains("move sources"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"modbus_tcp", "excel", "text"})
    void comCdIsRequiredOnEachCollectionEntry(String type) throws Exception {
        Path file = defaults("company.json");
        String contents =
                type.equals("modbus_tcp")
                        ? "{\"collectionType\":\"modbus_tcp\",\"nodes\":[{\"nodeId\":\"ND01\",\"host\":\"localhost\",\"readBlocks\":[{\"registerCount\":1}]}]}"
                        : "{\"collectionType\":\""
                                + type
                                + "\",\"sources\":["
                                + SOURCE.replace("\"type\":\"text\",", "")
                                        .replace("\"com_cd\":\"company\",", "")
                                + "]}";
        Files.writeString(folder.resolve("company.json"), contents);
        var error = assertThrows(IllegalArgumentException.class, () -> CollectorConfig.load(file));
        assertTrue(error.getMessage().contains("com_cd is required"));
        assertTrue(error.getMessage().contains("company.json"));
    }

    @Test
    void comCdBelongsToEachNodeAndHasNoSiteDefault() throws Exception {
        Path file = defaults("company.json");
        Files.writeString(
                folder.resolve("company.json"),
                """
                {"collectionType":"modbus_tcp","nodes":[
                  {"nodeId":"ND01","com_cd":"company","host":"localhost","readBlocks":[{"registerCount":1}]},
                  {"nodeId":"ND02","com_cd":"company02","host":"localhost","readBlocks":[{"registerCount":1}]}
                ]}
                """);
        var nodes = CollectorConfig.load(file).config().nodes;
        assertEquals("company", nodes.get(0).comCd);
        assertEquals("company02", nodes.get(1).comCd);
        Files.writeString(file, "{\"sourceFile\":\"company.json\",\"siteId\":\"standard\"}");
        assertThrows(IllegalArgumentException.class, () -> CollectorConfig.load(file));
    }

    @Test
    void differentCompaniesShareTheCommonStatusAddress() throws Exception {
        Path file = defaults("hub.json");
        Files.writeString(
                folder.resolve("hub.json"),
                """
                {"collectionType":"modbus_tcp","nodes":[
                  {"nodeId":"ND01","com_cd":"first","host":"localhost","readBlocks":[{"startAddress":1,"registerCount":10}]},
                  {"nodeId":"ND01","com_cd":"second","host":"localhost","readBlocks":[{"startAddress":1,"registerCount":10}]}
                ]}
                """);
        var config = CollectorConfig.load(file).config();
        assertEquals(2, config.nodes.size());
        assertEquals(1, config.statusAddress);
        Files.writeString(file, "{\"sourceFile\":\"hub.json\",\"statusAddress\":9}");
        assertEquals(9, CollectorConfig.load(file).config().statusAddress);
        config.statusAddress = 65536;
        assertThrows(IllegalArgumentException.class, config::validate);
        Files.writeString(
                folder.resolve("hub.json"),
                Files.readString(folder.resolve("hub.json"))
                        .replace("\"collectionType\"", "\"statusAddress\":1,\"collectionType\""));
        var error = assertThrows(IllegalArgumentException.class, () -> CollectorConfig.load(file));
        assertTrue(error.getMessage().contains("statusAddress belongs only in collector.json"));
    }

    @Test
    void wordOrderIsSelectedPerCompanyNode() throws Exception {
        Path folder = Files.createDirectories(this.folder.resolve("word-order"));
        Path file =
                Files.writeString(
                        folder.resolve("collector.json"), "{\"sourceFile\":\"companies.json\"}");
        Path selected = folder.resolve("companies.json");
        String profile =
                """
                {"collectionType":"modbus_tcp","nodes":[
                  {"nodeId":"ND01","com_cd":"first","host":"localhost","readBlocks":[{"startAddress":1,"registerCount":3}]},
                  {"nodeId":"ND01","com_cd":"second","host":"localhost","counterWordOrder":"LOW_HIGH","readBlocks":[{"startAddress":1,"registerCount":3}]}
                ]}
                """;
        Files.writeString(selected, profile);
        var config = CollectorConfig.load(file).config();
        assertEquals("HIGH_LOW", config.nodes.getFirst().counterWordOrder);
        assertEquals("LOW_HIGH", config.nodes.get(1).counterWordOrder);
        Files.writeString(selected, profile.replace("LOW_HIGH", "incorrect"));
        assertThrows(IllegalArgumentException.class, () -> CollectorConfig.load(file));
    }

    @Test
    void explicitlySelectedExcelAndTextCanShareOnePcProfile() throws Exception {
        Path file = defaults("companypc_01.json");
        String excel =
                SOURCE.replace("text_01", "excel_01")
                        .replace("\"type\":\"text\"", "\"type\":\"excel\"")
                        .replace("*.txt", "*.xlsx");
        Files.writeString(
                folder.resolve("companypc_01.json"),
                "{\"collectionType\":[\"text\",\"excel\"],\"sources\":["
                        + SOURCE
                        + ","
                        + excel
                        + "]}");
        var config = CollectorConfig.load(file).config();
        assertEquals(java.util.List.of("text", "excel"), config.collectionType);
        assertEquals(
                java.util.List.of("text", "excel"),
                config.sources.stream().map(s -> s.type).toList());
        Files.writeString(
                folder.resolve("ignored.json"), "This unrelated profile must not be read");
        assertEquals(2, CollectorConfig.load(file).config().sources.size());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"collectionType\":[],\"sources\":[]}",
                "{\"collectionType\":[\"text\",\"text\"],\"sources\":[]}",
                "{\"collectionType\":[\"text\",\"serial\"],\"sources\":[]}",
                "{\"collectionType\":[\"text\",null],\"sources\":[]}",
                "{\"collectionType\":[\"modbus_tcp\",\"text\"],\"nodes\":[],\"sources\":[]}",
                "{\"collectionType\":[\"text\",\"excel\"],\"sources\":[{\"id\":\"text_01\"}]}"
            })
    void invalidMultipleMethodSelectionFailsBeforeReadingFiles(String profile) throws Exception {
        Path file = defaults("companypc_01.json");
        Files.writeString(folder.resolve("companypc_01.json"), profile);
        assertThrows(IllegalArgumentException.class, () -> CollectorConfig.load(file));
    }
}

package io.databridge.ims;

import java.util.List;
import java.util.Map;

/** HTTP 요청 처리에 필요한 저장소 기능입니다. */
public interface HubRepository {
    void health() throws Exception;

    String ingest(Event event) throws Exception;

    String ingest(FileBatch batch) throws Exception;

    String ingest(ModbusData packet) throws Exception;

    List<Map<String, Object>> listJsonData(
            String company, String equipment, String source, String type, long after, int limit)
            throws Exception;

    List<Map<String, Object>> listRuns(
            String company, String equipment, String source, long after, int limit)
            throws Exception;

    List<Map<String, Object>> listStatus(
            String company, String equipment, String source, long after, int limit)
            throws Exception;
}

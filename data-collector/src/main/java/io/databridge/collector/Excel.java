package io.databridge.collector;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Excel 전체 행을 검증한 뒤 한 파일 요청에 사용할 이벤트를 반환합니다. */
public final class Excel {
    private final CollectorConfig config;
    private final CollectorConfig.Source source;

    public Excel(CollectorConfig config, CollectorConfig.Source source) {
        this.config = config;
        this.source = source;
    }

    public List<Event> read(Path file, String generation, String collectedAt) throws Exception {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls"))
            throw new IOException("Only .xlsx and .xls supported");
        var batch = new FileBatch.Builder(generation);
        var fields = source.allFields();
        try (var input = Files.newInputStream(file);
                var workbook = WorkbookFactory.create(input)) {
            Sheet sheet =
                    source.sheet.matches("[0-9]+")
                            ? workbook.getSheetAt(Integer.parseInt(source.sheet))
                            : workbook.getSheet(source.sheet);
            if (sheet == null) throw new IOException("Excel sheet not found");
            if (sheet.getLastRowNum() >= 100000) throw new IOException("Excel exceeds 100000 rows");
            int first = source.layout.equals("cells") ? 1 : source.firstDataRow;
            int last = source.layout.equals("cells") ? 1 : sheet.getLastRowNum() + 1;
            var formatter = new DataFormatter(Locale.ROOT);
            formatter.setUseCachedValuesForFormulaCells(true);
            if (source.layout.equals("rows"))
                for (var field : fields.values()) {
                    var reference =
                            field.headerCell == null
                                    ? new CellReference(first - 2, field.column - 1)
                                    : new CellReference(field.headerCell);
                    var headerRow =
                            reference.getRow() < 0 ? null : sheet.getRow(reference.getRow());
                    String title =
                            display(
                                    headerRow == null
                                            ? null
                                            : headerRow.getCell(reference.getCol()),
                                    formatter);
                    EventFactory.checkHeader(reference.formatAsString(), field.headerName, title);
                }
            for (int index = first; index <= last; index++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                try {
                    var raw = new LinkedHashMap<String, Object>();
                    for (var entry : fields.entrySet()) {
                        var field = entry.getValue();
                        CellReference reference =
                                source.layout.equals("cells")
                                        ? new CellReference(field.cell)
                                        : new CellReference(index - 1, field.column - 1);
                        Row row = sheet.getRow(reference.getRow());
                        raw.put(
                                entry.getKey(),
                                value(row == null ? null : row.getCell(reference.getCol())));
                    }
                    if (raw.values().stream()
                            .anyMatch(value -> value != null && !value.toString().isBlank()))
                        batch.add(
                                EventFactory.createFileRow(
                                        config,
                                        source,
                                        raw,
                                        new Event.Origin(
                                                file.getFileName().toString(), (long) index),
                                        generation,
                                        collectedAt));
                } catch (RuntimeException | IOException e) {
                    throw new IOException("Excel row " + index + ": " + e.getMessage(), e);
                }
            }
        }
        return batch.events();
    }

    private static String display(Cell cell, DataFormatter formatter) throws IOException {
        value(cell); // 오류 셀이나 계산 결과가 없는 수식을 정상 값처럼 저장하지 않습니다.
        return formatter.formatCellValue(cell);
    }

    private static Object value(Cell cell) throws IOException {
        if (cell == null) return null;
        var type = cell.getCellType();
        if (type == CellType.FORMULA) {
            // 저장된 계산 결과가 없는 수식은 측정값 0으로 처리하지 않습니다.
            if (cell instanceof org.apache.poi.xssf.usermodel.XSSFCell x && !x.getCTCell().isSetV())
                throw new IOException("Formula has no cached result; calculate and save workbook");
            type = cell.getCachedFormulaResultType();
        }
        return switch (type) {
            case NUMERIC ->
                    DateUtil.isCellDateFormatted(cell)
                            ? cell.getLocalDateTimeCellValue().toString()
                            : java.math.BigDecimal.valueOf(cell.getNumericCellValue());
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> cell.getBooleanCellValue();
            case BLANK, _NONE -> null;
            default -> throw new IOException("Excel error cell");
        };
    }
}

package io.databridge.collector;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** TXT 전체 행을 검증합니다. 이 단계에서는 전송하거나 처리 위치를 저장하지 않습니다. */
public final class Text {
    private final CollectorConfig config;
    private final CollectorConfig.Source source;

    public Text(CollectorConfig config, CollectorConfig.Source source) {
        this.config = config;
        this.source = source;
    }

    public List<Event> read(Path file, String generation, String collectedAt) throws Exception {
        if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
            throw new IOException("Only .txt supported");
        var fields = source.allFields();
        var batch = new FileBatch.Builder(generation);
        Pattern pattern = source.pattern == null ? null : Pattern.compile(source.pattern);
        Charset charset = Charset.forName(source.encoding);
        long line = 0;
        boolean checkedHeaders = pattern != null || source.skipLines == 0;
        try (var input = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] bytes;
            while ((bytes = readLine(input)) != null) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                String text =
                        charset.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes))
                                .toString()
                                .replaceFirst("[\\r\\n]+$", "");
                if (line == 0) text = text.replaceFirst("^\uFEFF", "");
                line++;
                if (line <= source.skipLines) {
                    if (line == source.skipLines && pattern == null) {
                        checkHeaders(
                                Arrays.asList(text.split(Pattern.quote(source.delimiter), -1)));
                        checkedHeaders = true;
                    }
                    continue;
                }
                if (text.isBlank()
                        || source.commentPrefix != null && text.startsWith(source.commentPrefix))
                    continue;
                try {
                    var raw = new LinkedHashMap<String, Object>();
                    if (pattern == null) {
                        String[] columns = text.split(Pattern.quote(source.delimiter), -1);
                        fields.forEach(
                                (name, field) ->
                                        raw.put(
                                                name,
                                                field.column <= columns.length
                                                        ? columns[field.column - 1]
                                                        : null));
                    } else {
                        var match = pattern.matcher(text);
                        if (!match.matches())
                            throw new IllegalArgumentException("Text does not match pattern");
                        fields.forEach((name, field) -> raw.put(name, match.group(field.group)));
                    }
                    batch.add(
                            EventFactory.createFileRow(
                                    config,
                                    source,
                                    raw,
                                    new Event.Origin(file.getFileName().toString(), line),
                                    generation,
                                    collectedAt));
                } catch (RuntimeException e) {
                    throw new IOException("Text line " + line + ": " + e.getMessage(), e);
                }
            }
        }
        if (!checkedHeaders) checkHeaders(List.of());
        return batch.events();
    }

    private void checkHeaders(List<String> headers) {
        for (var field : source.allFields().values())
            EventFactory.checkHeader(
                    "열 " + field.column,
                    field.headerName,
                    field.column <= headers.size() ? headers.get(field.column - 1) : "");
    }

    private static byte[] readLine(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != -1) {
            bytes.write(next);
            if (bytes.size() > 1048576) throw new IOException("Text line exceeds 1 MiB");
            if (next == '\n') break;
        }
        return bytes.size() == 0 ? null : bytes.toByteArray();
    }
}

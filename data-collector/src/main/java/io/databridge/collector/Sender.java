package io.databridge.collector;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.logging.Logger;

/** 데이터를 즉시 전송하고 IMS가 같은 이벤트를 확인한 뒤 반환합니다. 대기열은 사용하지 않습니다. */
public final class Sender implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(Sender.class.getName());
    private static final int MAX_RESPONSE_BYTES = 4096;
    private final CollectorConfig config;
    private final String token;
    private final HttpClient client;

    public Sender(CollectorConfig config) throws IOException {
        this(config, ApplicationProperties.loadToken());
    }

    public Sender(CollectorConfig config, String token) {
        ApplicationProperties.requireToken(token);
        this.config = config;
        this.token = token;
        client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(config.timeoutSeconds))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    public void send(Event event) throws Exception {
        send(event.eventId(), Json.write(event), false, null);
    }

    public Event.Ack send(FileBatch batch) throws Exception {
        String payload = Json.write(batch);
        if (payload.getBytes(StandardCharsets.UTF_8).length > FileBatch.MAX_BYTES)
            throw new IOException("FILE_BATCH_LIMIT [파일 요청 크기 초과] 최대 16MiB");
        return send(
                batch.eventId(),
                payload,
                false,
                "파일명="
                        + safeLogText(batch.events().getFirst().origin().fileName())
                        + " 행수="
                        + batch.events().size());
    }

    public Event.Ack send(ModbusData data) throws Exception {
        return send(data.eventId(), Json.write(data), true, null);
    }

    private Event.Ack send(String eventId, String payload, boolean modbus, String batchSummary)
            throws Exception {
        // 전송주소와 이벤트 ID로 요청을 구분합니다. 인증 헤더와 토큰은 로그에 포함하지 않습니다.
        String destination = "전송주소=" + config.endpoint + " 이벤트ID=" + eventId;
        var request =
                HttpRequest.newBuilder(URI.create(config.endpoint))
                        .timeout(Duration.ofSeconds(config.timeoutSeconds))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", eventId)
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                        .build();
        final HttpResponse<ResponseBody> response;
        try {
            response = client.send(request, ignored -> new LimitedBodySubscriber());
        } catch (IOException e) {
            throw new IOException("IMS_SEND_FAILED [IMS 전송 실패] " + destination + " 원인=" + e, e);
        }
        if (response.statusCode() != 200
                && response.statusCode() != 201
                && response.statusCode() != 202) {
            throw new IllegalStateException(
                    "IMS_SEND_FAILED [IMS 전송 실패] "
                            + destination
                            + " HTTP상태="
                            + response.statusCode()
                            + describeError(response.body()));
        }
        if (response.body().truncated())
            throw new IOException(
                    "IMS_SEND_FAILED [IMS 전송 실패] "
                            + destination
                            + " 원인=IMS 확인 응답이 4096바이트 제한을 초과했습니다");
        final Event.Ack ack;
        try {
            ack = Json.read(response.body().text(), Event.Ack.class);
        } catch (IOException e) {
            throw new IOException(
                    "IMS_SEND_FAILED [IMS 전송 실패] " + destination + " 원인=IMS 확인 응답의 JSON 형식 오류");
        }
        boolean standardStatus =
                ack != null
                        && ("accepted".equals(ack.status()) || "duplicate".equals(ack.status()));
        boolean partialStatus =
                modbus
                        && ack != null
                        && ("accepted_partial".equals(ack.status())
                                || "ignored_stale".equals(ack.status()));
        if (ack == null || !eventId.equals(ack.eventId()) || !(standardStatus || partialStatus)) {
            throw new IllegalStateException(
                    "IMS_SEND_FAILED [IMS 전송 실패] "
                            + destination
                            + " 원인=이벤트 ID 또는 처리 결과가 올바르지 않은 IMS 확인 응답");
        }
        if (partialStatus) {
            String result =
                    "ignored_stale".equals(ack.status())
                            ? "관측시각이 오래되어 전체 제외"
                            : "일부 데이터만 반영, 오래된 관측 제외";
            LOG.warning(
                    "IMS_SEND_RESULT [IMS 처리 결과] "
                            + destination
                            + " 상태="
                            + ack.status()
                            + " 결과="
                            + result);
            return ack;
        }
        // accepted는 신규 저장, duplicate는 이미 저장된 동일 이벤트입니다. 두 응답 모두 전송 완료로 처리합니다.
        String result = "accepted".equals(ack.status()) ? "신규 저장(accepted)" : "이미 저장됨(duplicate)";
        // 파일 요청은 최대 16MiB이므로 본문 대신 파일명과 행수만 기록합니다.
        LOG.info(
                "IMS_SEND_OK [IMS 전송 완료] "
                        + destination
                        + " 처리결과="
                        + result
                        + (batchSummary == null ? " 전송데이터=" + payload : " " + batchSummary));
        return ack;
    }

    private String describeError(ResponseBody body) {
        if (body.truncated()) return " 허브오류=응답 크기 제한 초과";
        try {
            var error = Json.MAPPER.readTree(body.text());
            if (error == null || !error.isObject()) return " 허브오류=구조화된 오류 응답 없음";
            var description = new StringBuilder();
            for (String field : List.of("code", "field", "message")) {
                var value = error.get(field);
                if (value != null && value.isTextual()) {
                    description
                            .append(' ')
                            .append(field)
                            .append('=')
                            .append(safeLogText(value.asText()));
                }
            }
            return description.isEmpty() ? " 허브오류=구조화된 오류 응답 없음" : description.toString();
        } catch (IOException e) {
            return " 허브오류=구조화된 오류 응답 없음";
        }
    }

    private String safeLogText(String value) {
        String redacted = value.replace(token, "[REDACTED]");
        var result = new StringBuilder();
        redacted.codePoints()
                .limit(256)
                .forEach(
                        character ->
                                result.appendCodePoint(
                                        Character.isISOControl(character)
                                                        || Character.getType(character)
                                                                == Character.FORMAT
                                                        || character == 0x2028
                                                        || character == 0x2029
                                                ? ' '
                                                : character));
        if (redacted.codePointCount(0, redacted.length()) > 256) result.append("...");
        return result.toString();
    }

    private record ResponseBody(String text, boolean truncated) {}

    private static final class LimitedBodySubscriber
            implements HttpResponse.BodySubscriber<ResponseBody> {
        private final CompletableFuture<ResponseBody> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<ResponseBody> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                int remaining = MAX_RESPONSE_BYTES - bytes.size();
                int length = Math.min(remaining, buffer.remaining());
                byte[] part = new byte[length];
                buffer.get(part);
                bytes.writeBytes(part);
                if (buffer.hasRemaining()) {
                    result.complete(new ResponseBody("", true));
                    subscription.cancel();
                    return;
                }
            }
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            result.complete(new ResponseBody(bytes.toString(StandardCharsets.UTF_8), false));
        }
    }

    @Override
    public void close() {
        client.close();
    }
}

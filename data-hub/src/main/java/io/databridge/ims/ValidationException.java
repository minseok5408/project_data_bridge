package io.databridge.ims;

/** 요청 오류에는 필드명과 정해진 안내 문구만 담고, 제출된 값은 노출하지 않습니다. */
public final class ValidationException extends IllegalArgumentException {
    private final String code;
    private final String field;

    public ValidationException(String field, String message) {
        this("INVALID_REQUEST", field, message);
    }

    public ValidationException(String code, String field, String message) {
        super(message);
        this.code = code;
        this.field = field;
    }

    public String code() {
        return code;
    }

    public String field() {
        return field;
    }

    public static void require(boolean condition, String field, String message) {
        if (!condition) throw new ValidationException(field, message);
    }
}

package top.aole.vend.common.exception;

import lombok.Getter;

/**
 * 业务异常:携带业务错误码,由 GlobalExceptionHandler 统一转成 R。
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public BizException(String message) {
        this(500, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}

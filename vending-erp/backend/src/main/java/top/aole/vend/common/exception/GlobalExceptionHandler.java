package top.aole.vend.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import top.aole.vend.common.result.R;

/**
 * 全局异常处理:所有异常统一转成 R{code,message,data} 返回。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常:预期内,warn 级别 */
    @ExceptionHandler(BizException.class)
    public R<Void> handleBizException(BizException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    /** 参数校验失败(@RequestBody @Valid) */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValidException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError == null ? "参数校验失败"
                : fieldError.getField() + " " + fieldError.getDefaultMessage();
        return R.fail(400, message);
    }

    /** 参数绑定失败(表单/query) */
    @ExceptionHandler(BindException.class)
    public R<Void> handleBindException(BindException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError == null ? "参数绑定失败"
                : fieldError.getField() + " " + fieldError.getDefaultMessage();
        return R.fail(400, message);
    }

    /** 兜底:未知异常,error 级别留栈 */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return R.fail("系统繁忙,请稍后重试");
    }
}

package cn.demo.common;

import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将业务冲突和请求校验异常转换为统一的接口错误响应。
 *
 * @author liusw144
 */
@RestControllerAdvice
public class ApiErrors {
    /** 将业务状态冲突转换为冲突响应。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<?> business(BusinessException exception) {
        return ResponseEntity.status(409).body(Map.of("error", exception.getMessage()));
    }

    /** 提示调用方复用业务编号查询原订单，避免重复下单。 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<?> duplicate() {
        return ResponseEntity.status(409).body(Map.of("error", "业务号已存在，请查询原订单结果，不能换号盲目重试"));
    }

    /** 将校验失败及不可解析的请求转换为参数错误响应。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    public ResponseEntity<?> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "参数非法，请检查业务编号、数量、金额和故障枚举"));
    }
}

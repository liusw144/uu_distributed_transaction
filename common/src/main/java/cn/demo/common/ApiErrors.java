package cn.demo.common;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<?> business(BusinessException e) {
        return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<?> duplicate() {
        return ResponseEntity.status(409).body(Map.of("error", "业务号已存在，请查询原订单结果，不能换号盲目重试"));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<?> invalid(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", "参数非法，请检查业务编号、数量、金额和故障枚举"));
    }
}

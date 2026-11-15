package cn.demo.common;

/**
 * 表示请求与业务约束或当前事务状态冲突。
 *
 * @author liusw144
 */
public class BusinessException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public BusinessException(String message) {
        super(message);
    }
}

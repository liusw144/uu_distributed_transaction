package cn.demo.seata.saga;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import cn.demo.common.ApiErrors;

/**
 * Seata Saga 状态机示例启动入口。
 *
 * @author liusw144
 */
@SpringBootApplication
@Import(ApiErrors.class)
public class SagaApplication {
    /**
     * 启动示例应用。
     *
     * @param args
     *            应用启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(SagaApplication.class, args);
    }
}

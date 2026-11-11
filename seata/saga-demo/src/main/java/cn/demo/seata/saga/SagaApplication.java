package cn.demo.seata.saga;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import cn.demo.common.ApiErrors;

@SpringBootApplication
@Import(ApiErrors.class)
public class SagaApplication {
    public static void main(String[] args) {
        SpringApplication.run(SagaApplication.class, args);
    }
}

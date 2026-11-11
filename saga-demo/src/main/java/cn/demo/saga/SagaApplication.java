package cn.demo.saga;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "cn.demo", exclude = DataSourceAutoConfiguration.class)
public class SagaApplication {
    public static void main(String[] args) {
        SpringApplication.run(SagaApplication.class, args);
    }
}

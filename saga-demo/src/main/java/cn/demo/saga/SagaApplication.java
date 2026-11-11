package cn.demo.saga;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动通过正向动作和逆序补偿实现一致性的 Saga 示例。
 *
 * @author liusw144
 */
@EnableScheduling
@SpringBootApplication(scanBasePackages = "cn.demo", exclude = DataSourceAutoConfiguration.class)
public class SagaApplication {
    /**
     * 启动应用并加载命令行配置。
     *
     * @param args
     *            命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(SagaApplication.class, args);
    }
}

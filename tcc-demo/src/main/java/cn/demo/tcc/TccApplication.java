package cn.demo.tcc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动使用预留、确认和取消阶段的 TCC 示例。
 *
 * @author liusw144
 */
@EnableScheduling
@SpringBootApplication(scanBasePackages = "cn.demo", exclude = DataSourceAutoConfiguration.class)
public class TccApplication {
    /**
     * 启动应用并加载命令行配置。
     *
     * @param args
     *            命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(TccApplication.class, args);
    }
}

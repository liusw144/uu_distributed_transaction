package cn.demo.xa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * 启动基于 Atomikos 的 XA 两阶段提交示例。
 *
 * @author liusw144
 */
@SpringBootApplication(scanBasePackages = "cn.demo", exclude = DataSourceAutoConfiguration.class)
public class XaApplication {
    /**
     * 启动应用并加载命令行配置。
     *
     * @param args
     *            命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(XaApplication.class, args);
    }
}

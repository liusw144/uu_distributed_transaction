package cn.demo.at;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * 启动 Seata AT 订单服务；库存和账户服务使用各自的启动入口。
 *
 * @author liusw144
 */
@SpringBootApplication(scanBasePackages = "cn.demo", exclude = DataSourceAutoConfiguration.class)
public class SeataApplication {
    /**
     * 启动应用并加载命令行配置。
     *
     * @param args
     *            命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(SeataApplication.class, args);
    }
}

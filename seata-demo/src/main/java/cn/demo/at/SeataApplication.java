package cn.demo.at;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * 默认启动订单服务；库存和账户分别直接运行 SeataStockApplication、SeataAccountApplication。
 */
@SpringBootApplication(scanBasePackages = "cn.demo", exclude = DataSourceAutoConfiguration.class)
public class SeataApplication {
    public static void main(String[] args) {
        SpringApplication.run(SeataApplication.class, args);
    }
}

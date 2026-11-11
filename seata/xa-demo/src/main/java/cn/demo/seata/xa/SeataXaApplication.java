package cn.demo.seata.xa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * 一个应用中的 MySQL、PostgreSQL 是两个独立事务资源，由真正的 Seata TC 协调。
 *
 * @author liusw144
 */
@SpringBootApplication(scanBasePackages = {"cn.demo.seata.xa", "cn.demo.common"},
        exclude = DataSourceAutoConfiguration.class)
public class SeataXaApplication {
    /**
     * 启动示例应用。
     *
     * @param args
     *            应用启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(SeataXaApplication.class, args);
    }
}

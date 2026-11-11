package cn.demo.seata.at;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * 一个应用中的 MySQL、PostgreSQL 是两个独立事务资源，由真正的 Seata TC 协调。
 */
@SpringBootApplication(scanBasePackages = {"cn.demo.seata.at", "cn.demo.common"}, exclude = DataSourceAutoConfiguration.class)
public class AtApplication {
    public static void main(String[] args) {
        SpringApplication.run(AtApplication.class, args);
    }
}


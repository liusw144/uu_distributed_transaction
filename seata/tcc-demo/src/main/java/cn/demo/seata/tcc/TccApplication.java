package cn.demo.seata.tcc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Seata TCC 分布式事务示例启动入口。
 *
 * @author liusw144
 */
@SpringBootApplication(scanBasePackages = {"cn.demo.seata.tcc", "cn.demo.common"})
public class TccApplication {
    /**
     * 启动示例应用。
     *
     * @param args
     *            应用启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(TccApplication.class, args);
    }
}

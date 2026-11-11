package cn.demo.seata.tcc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"cn.demo.seata.tcc", "cn.demo.common"})
public class TccApplication {
    public static void main(String[] args) {
        SpringApplication.run(TccApplication.class, args);
    }
}

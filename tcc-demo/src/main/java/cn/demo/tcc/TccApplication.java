package cn.demo.tcc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "cn.demo", exclude = DataSourceAutoConfiguration.class)
public class TccApplication {
    public static void main(String[] args) {
        SpringApplication.run(TccApplication.class, args);
    }
}

package cn.demo.notification;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;
@EnableScheduling
@SpringBootApplication(scanBasePackages="cn.demo",exclude=DataSourceAutoConfiguration.class)
public class NotificationApplication { public static void main(String[] args) { SpringApplication.run(NotificationApplication.class,args); } }

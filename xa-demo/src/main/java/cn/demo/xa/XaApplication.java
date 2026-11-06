package cn.demo.xa;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
@SpringBootApplication(scanBasePackages="cn.demo",exclude=DataSourceAutoConfiguration.class)
public class XaApplication { public static void main(String[] args) { SpringApplication.run(XaApplication.class,args); } }

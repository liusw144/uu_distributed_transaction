package cn.demo.at;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
/** 同一个 jar 分别以 order、stock、account 三种角色启动，形成三个独立 HTTP 服务。 */
@SpringBootApplication(scanBasePackages="cn.demo",exclude=DataSourceAutoConfiguration.class)
public class SeataApplication { public static void main(String[] args) { SpringApplication.run(SeataApplication.class,args); } }

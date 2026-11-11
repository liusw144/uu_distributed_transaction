package cn.demo.at;

import org.springframework.boot.SpringApplication;

/**
 * IDEA 直接运行此主类，自动加载 application-account.yml，启动账户分支服务。
 */
public class SeataAccountApplication {
    public static void main(String[] args) {
        var app = new SpringApplication(SeataApplication.class);
        app.setAdditionalProfiles("account");
        app.run(args);
    }
}

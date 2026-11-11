package cn.demo.at;

import org.springframework.boot.SpringApplication;

/**
 * IDEA 直接运行此主类，自动加载 application-stock.yml，启动库存分支服务。
 */
public class SeataStockApplication {
    public static void main(String[] args) {
        var app = new SpringApplication(SeataApplication.class);
        app.setAdditionalProfiles("stock");
        app.run(args);
    }
}

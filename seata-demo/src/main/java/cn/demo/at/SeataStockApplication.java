package cn.demo.at;

import org.springframework.boot.SpringApplication;

/**
 * 加载库存服务配置，启动 Seata AT 库存分支。
 *
 * @author liusw144
 */
public class SeataStockApplication {
    /**
     * 使用对应分支配置启动服务。
     *
     * @param args
     *            命令行参数
     */
    public static void main(String[] args) {
        var application = new SpringApplication(SeataApplication.class);
        application.setAdditionalProfiles("stock");
        application.run(args);
    }
}

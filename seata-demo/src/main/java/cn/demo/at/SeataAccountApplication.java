package cn.demo.at;

import org.springframework.boot.SpringApplication;

/**
 * 加载账户服务配置，启动 Seata AT 账户分支。
 *
 * @author liusw144
 */
public class SeataAccountApplication {
    /**
     * 使用对应分支配置启动服务。
     *
     * @param args
     *            命令行参数
     */
    public static void main(String[] args) {
        var application = new SpringApplication(SeataApplication.class);
        application.setAdditionalProfiles("account");
        application.run(args);
    }
}

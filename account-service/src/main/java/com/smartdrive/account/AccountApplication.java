package com.smartdrive.account;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 扫描 common-api 以启用统一异常处理；其余组件都在本服务包内 */
@SpringBootApplication(scanBasePackages = {"com.smartdrive.account", "com.smartdrive.common.api"})
public class AccountApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountApplication.class, args);
    }
}

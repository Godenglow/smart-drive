package com.smartdrive.account.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** springdoc 文档基础信息；按服务分别配置，不做跨服务聚合 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI accountServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("SmartDrive 账户服务 API")
                .version("v1")
                .description("注册与账户查询（登录、JWT Bearer 鉴权在 Day 5 接入）"));
    }
}

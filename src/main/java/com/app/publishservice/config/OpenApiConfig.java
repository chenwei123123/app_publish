package com.app.publishservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        Server server = new Server()
                .url("/")
                .description("当前访问地址");
        return new OpenAPI()
                .info(new Info()
                        .title("应用发布服务 API")
                        .version("1.0.0")
                        .description("当前应用发布服务接口的 OpenAPI 定义。"))
                .servers(List.of(server));
    }
}

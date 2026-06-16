package com.app.publishservice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
@MapperScan("com.app.publishservice.repository")
public class AppPublishServiceApplication {

    /**
     * 启动应用入口。
     */
    public static void main(String[] args) {
        SpringApplication.run(AppPublishServiceApplication.class, args);
    }
}

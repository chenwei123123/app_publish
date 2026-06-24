package com.app.publishservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    private final AppProperties appProperties;

    /**
     * 初始化RestClientConfig。
     */
    public RestClientConfig(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * 处理rest 客户端相关逻辑。
     */
    @Bean
    public RestClient restClient() {
        Duration timeout = Duration.ofSeconds(Math.max(appProperties.getStoreApi().getDefaultTimeoutSeconds(), 1));
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        // Spring 的 JDK 请求工厂会在整个请求生命周期内统一应用该超时。
        factory.setReadTimeout(timeout);

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}

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

    public RestClientConfig(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Bean
    public RestClient restClient() {
        Duration timeout = Duration.ofSeconds(Math.max(appProperties.getStoreApi().getDefaultTimeoutSeconds(), 1)*1000);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        // Spring's JDK request factory enforces this timeout across the whole request lifecycle.
        factory.setReadTimeout(timeout);

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}

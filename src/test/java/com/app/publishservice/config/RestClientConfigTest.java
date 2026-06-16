package com.app.publishservice.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class RestClientConfigTest {

    /**
     * 测试Apply 请求超时 Whole Call场景。
     */
    @Test
    void shouldApplyRequestTimeoutToWholeCall() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(5000);
                byte[] response = "slow response".getBytes();
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            AppProperties appProperties = new AppProperties();
            appProperties.getStoreApi().setDefaultTimeoutSeconds(1);
            RestClient restClient = new RestClientConfig(appProperties).restClient();

            assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertThrows(
                            ResourceAccessException.class,
                            () -> restClient.get()
                                    .uri("http://127.0.0.1:" + server.getAddress().getPort() + "/slow")
                                    .retrieve()
                                    .body(String.class)
                    )
            );
        } finally {
            server.stop(0);
        }
    }
}

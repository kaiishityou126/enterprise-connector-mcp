package com.sea.star.ai.ec.enterprise.connector.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 共享 WebClient.Builder；HttpApiAdapter 每次调用时按租户定制 baseUrl / header / body 大小。
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}

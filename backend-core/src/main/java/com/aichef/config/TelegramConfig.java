package com.aichef.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class TelegramConfig {

    @Bean
    RestClient telegramRestClient(TelegramProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.apiBase())
                .build();
    }
}

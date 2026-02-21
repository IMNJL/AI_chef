package com.aichef.miniapp;

import com.aichef.config.AiProperties;
import com.aichef.config.GoogleCalendarProperties;
import com.aichef.config.TelegramProcessingConfig;
import com.aichef.config.TelegramProperties;
import com.aichef.config.TelegramWebhookRegistrar;
import com.aichef.controller.GoogleOAuthController;
import com.aichef.controller.TelegramWebhookController;
import com.aichef.service.NotificationDispatchService;
import com.aichef.service.TelegramBotService;
import com.aichef.service.TelegramPollingService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.aichef")
@EntityScan("com.aichef.domain")
@EnableJpaRepositories("com.aichef.repository")
@EnableConfigurationProperties({TelegramProperties.class, AiProperties.class, GoogleCalendarProperties.class})
@ComponentScan(
        basePackages = "com.aichef",
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                        TelegramWebhookRegistrar.class,
                        TelegramProcessingConfig.class,
                        TelegramPollingService.class,
                        TelegramWebhookController.class,
                        TelegramBotService.class,
                        NotificationDispatchService.class,
                        GoogleOAuthController.class
                })
        }
)
public class MiniAppBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniAppBackendApplication.class, args);
    }
}

package com.aichef.telegram;

import com.aichef.config.AiProperties;
import com.aichef.config.GoogleCalendarProperties;
import com.aichef.config.MiniAppWebConfig;
import com.aichef.config.TelegramProperties;
import com.aichef.controller.MiniAppMeController;
import com.aichef.controller.MiniAppMeetingController;
import com.aichef.controller.MiniAppNoteController;
import com.aichef.controller.MiniAppTaskController;
import com.aichef.service.MiniAppAuthService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.aichef")
@EnableScheduling
@EntityScan("com.aichef.domain")
@EnableJpaRepositories("com.aichef.repository")
@EnableConfigurationProperties({TelegramProperties.class, AiProperties.class, GoogleCalendarProperties.class})
@ComponentScan(
        basePackages = "com.aichef",
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                        MiniAppWebConfig.class,
                        MiniAppAuthService.class,
                        MiniAppMeController.class,
                        MiniAppTaskController.class,
                        MiniAppMeetingController.class,
                        MiniAppNoteController.class
                })
        }
)
public class TelegramBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(TelegramBackendApplication.class, args);
    }
}

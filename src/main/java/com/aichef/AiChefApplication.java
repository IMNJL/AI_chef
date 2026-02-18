package com.aichef;

import com.aichef.config.AiProperties;
import com.aichef.config.GoogleCalendarProperties;
import com.aichef.config.TelegramProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({TelegramProperties.class, AiProperties.class, GoogleCalendarProperties.class})
public class AiChefApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiChefApplication.class, args);
    }
}

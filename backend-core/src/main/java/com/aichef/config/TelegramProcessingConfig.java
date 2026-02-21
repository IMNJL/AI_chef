package com.aichef.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class TelegramProcessingConfig {

    @Bean(name = "telegramUpdateExecutor")
    public Executor telegramUpdateExecutor(
            @Value("${app.telegram.processing-threads:4}") int processingThreads,
            @Value("${app.telegram.processing-queue-capacity:500}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int threads = Math.max(1, processingThreads);
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(Math.max(50, queueCapacity));
        executor.setThreadNamePrefix("tg-update-");
        executor.initialize();
        return executor;
    }
}

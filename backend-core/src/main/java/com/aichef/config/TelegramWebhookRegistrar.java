package com.aichef.config;

import com.aichef.service.TelegramBotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.net.URI;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramWebhookRegistrar implements ApplicationRunner {

    private final TelegramProperties properties;
    private final TelegramBotService telegramBotService;

    @Override
    public void run(ApplicationArguments args) {
        String baseUrl = properties.publicBaseUrl();
        log.info("Telegram config: botUsername={}, webhookPath={}, hasPublicBaseUrl={}, hasToken={}",
                properties.botUsername(),
                properties.webhookPath(),
                baseUrl != null && !baseUrl.isBlank(),
                properties.botToken() != null && !properties.botToken().isBlank());
        telegramBotService.configureMiniAppEntryPoints();

        String webhookBaseUrl = resolveWebhookBaseUrl(baseUrl);
        if (webhookBaseUrl == null) {
            log.info("Webhook is disabled for current APP_PUBLIC_BASE_URL. Local mode: deleting webhook and enabling polling.");
            telegramBotService.deleteWebhook(false);
            telegramBotService.logWebhookInfo();
            return;
        }

        String webhookUrl = webhookBaseUrl + properties.webhookPath();
        try {
            telegramBotService.registerWebhook(webhookUrl);
            log.info("Telegram webhook registered: {}", webhookUrl);
            telegramBotService.logWebhookInfo();
        } catch (Exception e) {
            log.error("Webhook registration failed. Fallback to local polling mode. error={}", e.getMessage());
            telegramBotService.deleteWebhook(false);
            telegramBotService.logWebhookInfo();
        }
    }

    private String resolveWebhookBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            String path = uri.getPath();

            if (scheme == null || host == null) {
                log.warn("APP_PUBLIC_BASE_URL is invalid: {}", baseUrl);
                return null;
            }
            if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host)) {
                return null;
            }
            if (path != null && !path.isBlank() && !"/".equals(path)) {
                log.warn("APP_PUBLIC_BASE_URL contains path '{}'. Using origin only for webhook registration.", path);
            }

            StringBuilder origin = new StringBuilder(scheme).append("://").append(host);
            if (port > 0) {
                origin.append(":").append(port);
            }
            return origin.toString();
        } catch (Exception e) {
            log.warn("Failed to parse APP_PUBLIC_BASE_URL '{}': {}", baseUrl, e.getMessage());
            return null;
        }
    }
}

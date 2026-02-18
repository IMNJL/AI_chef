package com.aichef.service;

import com.aichef.config.TelegramProperties;
import com.aichef.dto.TelegramWebhookUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramPollingService {

    private final TelegramProperties properties;
    private final RestClient telegramRestClient;
    private final TelegramBotService telegramBotService;
    private final ObjectMapper objectMapper;

    private final AtomicLong offset = new AtomicLong(0);
    private final AtomicBoolean pollingConflictLogged = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "${app.telegram.poll-interval-ms:3000}")
    public void pollUpdates() {
        if (isWebhookModeEnabled(properties.publicBaseUrl())) {
            return;
        }

        try {
            Map<?, ?> response = telegramRestClient.get()
                    .uri("/bot{token}/getUpdates?offset={offset}&timeout=20", properties.botToken(), offset.get())
                    .retrieve()
                    .body(Map.class);

            if (response == null || !Boolean.TRUE.equals(response.get("ok"))) {
                log.warn("Telegram getUpdates returned unexpected response={}", response);
                return;
            }

            Object resultObj = response.get("result");
            if (!(resultObj instanceof List<?> results) || results.isEmpty()) {
                return;
            }

            for (Object updateObj : results) {
                TelegramWebhookUpdate update = objectMapper.convertValue(updateObj, TelegramWebhookUpdate.class);
                if (update.update_id() != null) {
                    offset.set(Math.max(offset.get(), update.update_id() + 1));
                }
                log.info("Polled Telegram update. updateId={}, hasMessage={}",
                        update.update_id(), update.message() != null);
                telegramBotService.handleUpdate(update);
            }
        } catch (HttpClientErrorException.Conflict e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("terminated by other getUpdates request")) {
                if (pollingConflictLogged.compareAndSet(false, true)) {
                    log.error("Polling conflict: another bot instance is running. Stop extra instance to continue polling.");
                }
                return;
            }
            log.error("Failed to poll getUpdates. error={}", e.getMessage(), e);
        } catch (RestClientException e) {
            log.error("Failed to poll getUpdates. error={}", e.getMessage(), e);
        }
    }

    private boolean isWebhookModeEnabled(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            return !("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host));
        } catch (Exception e) {
            return false;
        }
    }
}

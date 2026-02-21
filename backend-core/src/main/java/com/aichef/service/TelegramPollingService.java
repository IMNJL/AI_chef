package com.aichef.service;

import com.aichef.config.TelegramProperties;
import com.aichef.dto.TelegramWebhookUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class TelegramPollingService {

    private final TelegramProperties properties;
    private final RestClient telegramRestClient;
    private final TelegramBotService telegramBotService;
    private final ObjectMapper objectMapper;
    private final Executor telegramUpdateExecutor;

    @Autowired
    public TelegramPollingService(TelegramProperties properties,
                                  RestClient telegramRestClient,
                                  TelegramBotService telegramBotService,
                                  ObjectMapper objectMapper,
                                  @Qualifier("telegramUpdateExecutor") Executor telegramUpdateExecutor) {
        this.properties = properties;
        this.telegramRestClient = telegramRestClient;
        this.telegramBotService = telegramBotService;
        this.objectMapper = objectMapper;
        this.telegramUpdateExecutor = telegramUpdateExecutor;
    }

    private final AtomicLong offset = new AtomicLong(0);
    private final AtomicBoolean pollingConflictLogged = new AtomicBoolean(false);
    private final AtomicInteger networkErrorStreak = new AtomicInteger(0);
    private final AtomicLong nextPollAllowedAtMs = new AtomicLong(0);

    @Scheduled(fixedDelayString = "${app.telegram.poll-interval-ms:3000}")
    public void pollUpdates() {
        if (isWebhookModeEnabled(properties.publicBaseUrl())) {
            return;
        }
        if (System.currentTimeMillis() < nextPollAllowedAtMs.get()) {
            return;
        }

        try {
            Map<?, ?> response = telegramRestClient.get()
                    .uri("/bot{token}/getUpdates?offset={offset}&timeout=20", properties.botToken(), offset.get())
                    .retrieve()
                    .body(Map.class);

            resetNetworkBackoffIfNeeded();
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
                telegramUpdateExecutor.execute(() -> {
                    try {
                        telegramBotService.handleUpdate(update);
                    } catch (Exception e) {
                        log.error("Failed to process Telegram update asynchronously. updateId={}, error={}",
                                update.update_id(), e.getMessage(), e);
                    }
                });
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
        } catch (ResourceAccessException e) {
            applyNetworkBackoff(e);
        } catch (RestClientException e) {
            log.error("Failed to poll getUpdates. error={}", e.getMessage(), e);
        }
    }

    private void applyNetworkBackoff(ResourceAccessException e) {
        int streak = networkErrorStreak.incrementAndGet();
        long backoffMs = calculateBackoffMs(streak);
        nextPollAllowedAtMs.set(System.currentTimeMillis() + backoffMs);
        Throwable root = rootCause(e);
        log.warn("Telegram getUpdates network error: {}. Retry in {} ms (attempt #{})",
                summarizeCause(root), backoffMs, streak);
        if (log.isDebugEnabled()) {
            log.debug("Telegram polling network error stack trace", e);
        }
    }

    private void resetNetworkBackoffIfNeeded() {
        int streak = networkErrorStreak.getAndSet(0);
        nextPollAllowedAtMs.set(0);
        if (streak > 0) {
            log.info("Telegram polling connection restored after {} consecutive network errors", streak);
        }
    }

    private long calculateBackoffMs(int streak) {
        int exponent = Math.min(6, Math.max(0, streak - 1));
        return Math.min(60_000L, 1_000L * (1L << exponent));
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String summarizeCause(Throwable cause) {
        if (cause == null) {
            return "unknown";
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        return cause.getClass().getSimpleName() + ": " + message;
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

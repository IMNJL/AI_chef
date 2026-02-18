package com.aichef.controller;

import com.aichef.config.TelegramProperties;
import com.aichef.dto.TelegramWebhookUpdate;
import com.aichef.service.TelegramBotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("${app.telegram.webhook-path:/api/telegram/webhook}")
public class TelegramWebhookController {

    private final TelegramProperties properties;
    private final TelegramBotService telegramBotService;

    @PostMapping
    public ResponseEntity<Void> webhook(
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secretToken,
            @RequestBody TelegramWebhookUpdate update
    ) {
        if (!properties.webhookSecret().equals(secretToken)) {
            log.warn("Rejected Telegram webhook: invalid secret token. got={}, expectedLength={}",
                    secretToken == null ? "null" : "***",
                    properties.webhookSecret().length());
            return ResponseEntity.status(403).build();
        }

        Long chatId = update != null && update.message() != null && update.message().chat() != null
                ? update.message().chat().id()
                : null;
        log.info("Accepted Telegram webhook. chatId={}, hasMessage={}, hasText={}",
                chatId,
                update != null && update.message() != null,
                update != null && update.message() != null && update.message().text() != null);

        telegramBotService.handleUpdate(update);
        return ResponseEntity.ok().build();
    }
}

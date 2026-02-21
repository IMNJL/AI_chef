package com.aichef.controller;

import com.aichef.service.GoogleOAuthService;
import com.aichef.service.TelegramBotService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/google")
public class GoogleOAuthController {

    private final GoogleOAuthService googleOAuthService;
    private final TelegramBotService telegramBotService;

    @GetMapping("/connect")
    public ResponseEntity<?> connect(@RequestParam("telegramId") Long telegramId) {
        return googleOAuthService.createConnectUrl(telegramId)
                .map(url -> ResponseEntity.status(302)
                        .header(HttpHeaders.LOCATION, url)
                        .build())
                .orElseGet(() -> ResponseEntity.badRequest().body("Google OAuth is not configured. Check APP_PUBLIC_BASE_URL and Google credentials."));
    }

    @GetMapping("/callback")
    public ResponseEntity<String> callback(
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "error_description", required = false) String errorDescription
    ) {
        if (error != null && !error.isBlank()) {
            String details = (errorDescription == null || errorDescription.isBlank()) ? "" : (": " + errorDescription);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Google OAuth error: " + error + details + "\nReturn to Telegram bot and retry connect.");
        }

        GoogleOAuthService.OAuthCallbackResult result = googleOAuthService.handleCallback(state, code);
        if (result.connected() && result.telegramId() != null) {
            String icsUrl = googleOAuthService.createIcsUrl(result.telegramId()).orElse(null);
            telegramBotService.sendMessage(
                    result.telegramId(),
                    "✅ Google Calendar подключен. Синхронизация активна.",
                    true
            );
            if (icsUrl != null && !icsUrl.isBlank()) {
                telegramBotService.sendMessage(
                        result.telegramId(),
                        "📎 iCalendar подписка (read-only):\n" + icsUrl,
                        false
                );
            }
        }
        HttpStatus status = result.connected() ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(result.message() + "\nВернитесь в Telegram-бот и попробуйте снова.");
    }
}

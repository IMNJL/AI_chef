package com.aichef.controller;

import com.aichef.service.GoogleOAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
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
            @RequestParam("state") String state,
            @RequestParam("code") String code
    ) {
        String message = googleOAuthService.handleCallback(state, code);
        return ResponseEntity.ok(message + "\nReturn to Telegram bot.");
    }
}

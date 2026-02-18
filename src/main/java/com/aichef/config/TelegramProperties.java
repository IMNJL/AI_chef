package com.aichef.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.telegram")
public record TelegramProperties(
        @NotBlank String botToken,
        @NotBlank String botUsername,
        @NotBlank String webhookSecret,
        @NotBlank String webhookPath,
        @NotBlank String apiBase,
        String publicBaseUrl
) {
}

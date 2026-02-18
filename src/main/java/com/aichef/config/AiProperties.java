package com.aichef.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
        String geminiApiKey,
        String geminiModel,
        String geminiBaseUrl,
        String ollamaBaseUrl,
        String ollamaModel,
        String whisperCommand,
        String whisperModel
) {
    public boolean hasGeminiKey() {
        return geminiApiKey != null && !geminiApiKey.isBlank();
    }

    public boolean hasOllama() {
        return ollamaBaseUrl != null && !ollamaBaseUrl.isBlank() && ollamaModel != null && !ollamaModel.isBlank();
    }

    public boolean hasWhisperCommand() {
        return whisperCommand != null && !whisperCommand.isBlank();
    }
}

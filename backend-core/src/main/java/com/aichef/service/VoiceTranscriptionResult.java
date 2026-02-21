package com.aichef.service;

public record VoiceTranscriptionResult(
        String text,
        String telegramFileUrl,
        String mimeType,
        Integer durationSec
) {
}

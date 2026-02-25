package com.aichef.service;

import com.aichef.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class FallbackVoiceTranscriptionService implements VoiceTranscriptionService {

    private final AiProperties aiProperties;
    private final LocalVoskVoiceTranscriptionService localVoskVoiceTranscriptionService;
    private final LocalWhisperVoiceTranscriptionService localWhisperVoiceTranscriptionService;

    @Override
    public VoiceTranscriptionResult transcribe(String fileId, String mimeType, Integer durationSec) {
        Exception voskError = null;
        Exception whisperError = null;

        if (aiProperties.hasVoskModelPath()) {
            try {
                VoiceTranscriptionResult result = localVoskVoiceTranscriptionService.transcribe(fileId, mimeType, durationSec);
                log.info("STT engine=Vosk fileId={}", fileId);
                return result;
            } catch (Exception e) {
                voskError = e;
                log.warn("Vosk STT failed, trying next configured engine. fileId={}, error={}", fileId, e.getMessage());
            }
        }

        if (aiProperties.hasWhisperCommand()) {
            try {
                VoiceTranscriptionResult result = localWhisperVoiceTranscriptionService.transcribe(fileId, mimeType, durationSec);
                log.info("STT engine=Whisper fileId={}", fileId);
                return result;
            } catch (Exception e) {
                whisperError = e;
                log.warn("Whisper STT failed. error={}", e.getMessage());
            }
        }

        if (voskError != null || whisperError != null) {
            if (whisperError != null && voskError != null) {
                whisperError.addSuppressed(voskError);
                throw new IllegalStateException("Voice transcription failed in all configured engines.", whisperError);
            }
            Exception primary = whisperError != null ? whisperError : voskError;
            throw new IllegalStateException("Voice transcription failed in all configured engines.", primary);
        }
        throw new IllegalStateException("Voice transcription is unavailable. Configure APP_VOSK_MODEL_PATH or APP_WHISPER_COMMAND.");
    }
}

package com.aichef.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramWebhookUpdate(Long update_id, Message message) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(Long message_id, Chat chat, String text, String caption, Voice voice) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chat(Long id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Voice(String file_id, String mime_type, Integer duration, Integer file_size) {
    }
}

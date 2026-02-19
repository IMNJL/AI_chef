package com.aichef.service;

import com.aichef.config.TelegramProperties;
import com.aichef.domain.enums.FilterClassification;
import com.aichef.domain.enums.InboundStatus;
import com.aichef.domain.enums.MeetingStatus;
import com.aichef.domain.enums.PriorityLevel;
import com.aichef.domain.enums.RelatedType;
import com.aichef.domain.enums.SourceType;
import com.aichef.domain.model.CalendarDay;
import com.aichef.domain.model.EventCreationSession;
import com.aichef.domain.model.InboundItem;
import com.aichef.domain.model.Meeting;
import com.aichef.domain.model.Note;
import com.aichef.domain.model.NoteEditSession;
import com.aichef.domain.model.Notification;
import com.aichef.domain.model.TaskItem;
import com.aichef.domain.model.User;
import com.aichef.dto.TelegramWebhookUpdate;
import com.aichef.repository.CalendarDayRepository;
import com.aichef.repository.EventCreationSessionRepository;
import com.aichef.repository.InboundItemRepository;
import com.aichef.repository.MeetingRepository;
import com.aichef.repository.NoteRepository;
import com.aichef.repository.NoteEditSessionRepository;
import com.aichef.repository.NotificationRepository;
import com.aichef.repository.TaskItemRepository;
import com.aichef.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramBotService {
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Moscow");

    private static final int TELEGRAM_MESSAGE_MAX_CHARS = 4000;

    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?");
    private static final Pattern DATE_TEXT_PATTERN = Pattern.compile(
            "\\b(\\d{1,2})\\s+(январ[яе]|феврал[яе]|март[а]?|апрел[яе]|ма[йя]|июн[яе]|июл[яе]|август[а]?|сентябр[яе]|октябр[яе]|ноябр[яе]|декабр[яе])(?:\\s+(\\d{4}))?\\b");
    private static final Pattern DATE_WORDS_PATTERN = Pattern.compile(
            "(?iu)(?<!\\p{L})([а-яё\\-]+(?:\\s+[а-яё\\-]+)?)\\s+(январ[яе]|феврал[яе]|март[а]?|апрел[яе]|ма[йя]|июн[яе]|июл[яе]|август[а]?|сентябр[яе]|октябр[яе]|ноябр[яе]|декабр[яе])(?:\\s+([а-яё\\s\\-]+?)\\s+г(?:ода|од)?)?(?!\\p{L})");
    private static final Pattern TIME_COLON_PATTERN = Pattern.compile("\\b(?:в|на)?\\s*(\\d{1,2})[:.](\\d{2})\\b");
    private static final Pattern TIME_HOUR_ONLY_PATTERN = Pattern.compile("\\b(?:в|на)?\\s*(\\d{1,2})\\s*(?:час|часа|часов)\\b");
    private static final Pattern TIME_HOUR_WORDS_PATTERN = Pattern.compile("(?iu)(?<!\\p{L})(?:в\\s+)?([а-яё\\-]+(?:\\s+[а-яё\\-]+)?)\\s+час(?:а|ов)?(?!\\p{L})");
    private static final Pattern DURATION_MIN_PATTERN = Pattern.compile("\\b(\\d{1,3})\\s*мин(?:ут[аы]?)?\\b");
    private static final Pattern DURATION_HOUR_DECIMAL_PATTERN = Pattern.compile("\\b(\\d+)[,.](\\d)\\s*час");
    private static final Pattern DURATION_HOUR_PATTERN = Pattern.compile("\\b(\\d{1,2})\\s*час(?:а|ов)?\\b");
    private static final Pattern EVENT_WIZARD_TRIGGER_PATTERN = Pattern.compile(
            "(?iu)(созда(ть|й)|добав(ить|ь)|запланиру(й|йте|ю)|сдела(й|ть))\\s+(событи[еяю]|встреч[ауеи])");
    private static final Map<String, Integer> RUS_MONTHS = Map.ofEntries(
            Map.entry("январ", 1),
            Map.entry("феврал", 2),
            Map.entry("март", 3),
            Map.entry("апрел", 4),
            Map.entry("ма", 5),
            Map.entry("июн", 6),
            Map.entry("июл", 7),
            Map.entry("август", 8),
            Map.entry("сентябр", 9),
            Map.entry("октябр", 10),
            Map.entry("ноябр", 11),
            Map.entry("декабр", 12)
    );
    private static final Map<String, Integer> RU_DAY_WORDS = buildRuDayWords();
    private static final Map<String, Integer> RU_NUMBER_WORDS = buildRuNumberWords();

    private final RestClient telegramRestClient;
    private final TelegramProperties properties;
    private final UserRepository userRepository;
    private final InboundItemRepository inboundItemRepository;
    private final EventCreationSessionRepository eventCreationSessionRepository;
    private final CalendarDayRepository calendarDayRepository;
    private final MeetingRepository meetingRepository;
    private final NotificationRepository notificationRepository;
    private final TaskItemRepository taskItemRepository;
    private final NoteRepository noteRepository;
    private final NoteEditSessionRepository noteEditSessionRepository;
    private final MessageUnderstandingService messageUnderstandingService;
    private final OllamaStructuredParsingService ollamaStructuredParsingService;
    private final VoiceTranscriptionService voiceTranscriptionService;
    private final GoogleCalendarService googleCalendarService;
    private final GoogleOAuthService googleOAuthService;
    @Value("${app.miniapp.public-url:}")
    private String miniAppPublicUrl;

    @Transactional
    public void handleUpdate(TelegramWebhookUpdate update) {
        if (update == null || update.message() == null || update.message().chat() == null) {
            log.warn("Skip Telegram update: update/message/chat is null");
            return;
        }

        Long chatId = update.message().chat().id();
        String text = update.message().text();
        String caption = update.message().caption();
        boolean hasVoice = update.message().voice() != null;
        log.info("Handle Telegram update. chatId={}, hasText={}, hasCaption={}, hasVoice={}",
                chatId,
                text != null && !text.isBlank(),
                caption != null && !caption.isBlank(),
                hasVoice);

        User user = userRepository.findByTelegramId(chatId)
                .orElseGet(() -> {
                    User newUser = new User();
                    newUser.setTelegramId(chatId);
                    log.info("Create new user for chatId={}", chatId);
                    return userRepository.save(newUser);
                });

        SourceType sourceType;
        String rawText = text;
        String fileUrl = null;
        Map<String, Object> metadata = new HashMap<>();
        if ((rawText == null || rawText.isBlank()) && caption != null && !caption.isBlank()) {
            rawText = caption;
        }

        if (hasVoice) {
            sourceType = SourceType.VOICE;
            try {
                TelegramWebhookUpdate.Voice voice = update.message().voice();
                VoiceTranscriptionResult transcriptionResult = voiceTranscriptionService.transcribe(
                        voice.file_id(), voice.mime_type(), voice.duration());
                String transcriptionRaw = transcriptionResult.text();
                rawText = sanitizeRecognizedText(transcriptionRaw);
                fileUrl = transcriptionResult.telegramFileUrl();
                metadata.put("voice_duration_sec", transcriptionResult.durationSec());
                metadata.put("voice_mime_type", transcriptionResult.mimeType());
                metadata.put("voice_file_id", voice.file_id());
                metadata.put("transcription_raw", transcriptionRaw);
                metadata.put("transcription", rawText);
            } catch (Exception e) {
                log.error("Voice transcription failed. chatId={}, error={}", chatId, e.getMessage(), e);
                sendMessage(chatId, buildVoiceFailureMessage(e), true);
                return;
            }
        } else {
            sourceType = SourceType.TEXT;
        }

        if (rawText != null && "/start".equalsIgnoreCase(rawText.trim())) {
            sendStartFlow(chatId);
            return;
        }

        ZoneId zoneId = resolveZone(user.getTimezone());
        NoteEditSession noteEditSession = noteEditSessionRepository.findByUser(user).orElse(null);
        if (noteEditSession != null) {
            if (isCancelRequest(rawText)) {
                noteEditSessionRepository.delete(noteEditSession);
                saveInboundItem(user, sourceType, rawText, fileUrl, metadata,
                        FilterClassification.INFO_ONLY, InboundStatus.PROCESSED);
                sendMessage(chatId, "Редактирование заметки отменено.", true);
                return;
            }
            saveInboundItem(user, sourceType, rawText, fileUrl, metadata,
                    FilterClassification.ASK_CLARIFICATION, InboundStatus.NEEDS_CLARIFICATION);
            WizardResult result = processNoteEditStep(user, noteEditSession, rawText);
            sendMessage(chatId, result.message(), result.showMainKeyboard() ? buildMainKeyboard(chatId) : buildEventCreationKeyboard());
            return;
        }

        if (isStartNoteEditFlow(rawText)) {
            NoteEditSession session = new NoteEditSession();
            session.setUser(user);
            session.setStep(NoteEditStep.WAIT_NOTE_NUMBER);
            session.setMode(NoteEditMode.EDIT);
            noteEditSessionRepository.save(session);
            saveInboundItem(user, sourceType, rawText, fileUrl, metadata,
                    FilterClassification.ASK_CLARIFICATION, InboundStatus.NEEDS_CLARIFICATION);
            sendMessage(chatId,
                    "Редактирование заметки.\nШаг 1/2: отправьте номер заметки из списка (например: 3).",
                    buildEventCreationKeyboard());
            return;
        }

        if (isStartNoteDeleteFlow(rawText)) {
            NoteEditSession session = new NoteEditSession();
            session.setUser(user);
            session.setStep(NoteEditStep.WAIT_NOTE_NUMBER);
            session.setMode(NoteEditMode.DELETE);
            noteEditSessionRepository.save(session);
            saveInboundItem(user, sourceType, rawText, fileUrl, metadata,
                    FilterClassification.ASK_CLARIFICATION, InboundStatus.NEEDS_CLARIFICATION);
            sendMessage(chatId,
                    "Удаление заметки.\nШаг 1/2: отправьте номер заметки из списка (например: 3).",
                    buildEventCreationKeyboard());
            return;
        }

        if (isIcalSubscriptionRequest(rawText)) {
            String icsUrl = googleOAuthService.createIcsUrl(chatId).orElse(null);
            if (icsUrl == null || icsUrl.isBlank()) {
                sendMessage(chatId, "Сначала подключите Google Calendar, затем появится ссылка на iCal подписку.", true);
            } else {
                sendMessage(chatId, "📎 iCalendar подписка (read-only):\n" + icsUrl, true);
            }
            return;
        }

        if (isMiniAppLinkRequest(rawText)) {
            String miniAppUrl = buildMiniAppUrl();
            if (miniAppUrl == null || miniAppUrl.isBlank()) {
                sendMessage(chatId, "Mini App пока не настроен. Укажите MINIAPP_PUBLIC_URL.", true);
            } else {
                sendMessage(chatId, "Ссылка на Mini App:\n" + miniAppUrl, true);
            }
            return;
        }

        EventCreationSession session = eventCreationSessionRepository.findByUser(user).orElse(null);
        if (session != null) {
            if (isCancelRequest(rawText)) {
                eventCreationSessionRepository.delete(session);
                saveInboundItem(user, sourceType, rawText, fileUrl, metadata,
                        FilterClassification.INFO_ONLY, InboundStatus.PROCESSED);
                sendMessage(chatId, "Создание события отменено.", true);
                return;
            }

            saveInboundItem(user, sourceType, rawText, fileUrl, metadata,
                    FilterClassification.ASK_CLARIFICATION, InboundStatus.NEEDS_CLARIFICATION);
            WizardResult wizardResult = processEventWizardStep(user, session, rawText, zoneId);
            sendMessage(chatId, wizardResult.message(), wizardResult.showMainKeyboard() ? buildMainKeyboard(chatId) : buildEventCreationKeyboard());
            return;
        }

        if (shouldStartEventWizard(rawText)) {
            EventCreationSession newSession = new EventCreationSession();
            newSession.setUser(user);
            fillEventSessionFromInput(newSession, rawText, zoneId, true);
            EventCreationStep nextStep = nextMissingStep(newSession);
            newSession.setStep(nextStep == null ? EventCreationStep.WAIT_DATE : nextStep);
            eventCreationSessionRepository.save(newSession);
            saveInboundItem(user, sourceType, rawText, fileUrl, metadata,
                    FilterClassification.ASK_CLARIFICATION, InboundStatus.NEEDS_CLARIFICATION);
            WizardResult wizardResult = processEventWizardStep(user, newSession, rawText, zoneId);
            sendMessage(chatId, wizardResult.message(), wizardResult.showMainKeyboard() ? buildMainKeyboard(chatId) : buildEventCreationKeyboard());
            return;
        }

        MessageIntent intent = messageUnderstandingService.decide(rawText, zoneId);
        InboundItem item = saveInboundItem(user, sourceType, rawText, fileUrl, metadata, intent.classification(), intent.status());
        String response = applyIntent(user, item, intent);
        sendMessage(chatId, response, true);
    }

    public void sendMessage(Long chatId, String text) {
        sendMessage(chatId, text, false);
    }

    public void sendMessage(Long chatId, String text, boolean withKeyboard) {
        sendMessage(chatId, text, withKeyboard ? buildMainKeyboard(chatId) : null);
    }

    private void sendMessage(Long chatId, String text, Map<String, Object> replyMarkup) {
        String safeText = text == null ? "" : text;
        List<String> parts = splitTelegramText(safeText, TELEGRAM_MESSAGE_MAX_CHARS);
        log.info("Send Telegram message. chatId={}, textLength={}, parts={}", chatId, safeText.length(), parts.size());

        try {
            for (int i = 0; i < parts.size(); i++) {
                String part = parts.get(i);
                Map<String, Object> payload = new HashMap<>();
                payload.put("chat_id", chatId);
                payload.put("text", part);
                if (i == parts.size() - 1 && replyMarkup != null) {
                    payload.put("reply_markup", replyMarkup);
                }

                telegramRestClient.post()
                        .uri("/bot{token}/sendMessage", properties.botToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
            }
            log.info("Telegram message sent. chatId={}", chatId);
        } catch (RestClientException e) {
            log.error("Failed to send Telegram message. chatId={}, error={}", chatId, e.getMessage(), e);
            throw e;
        }
    }

    private Long sendMessageAndGetId(Long chatId, String text, Map<String, Object> replyMarkup) {
        String safeText = text == null ? "" : text;
        List<String> parts = splitTelegramText(safeText, TELEGRAM_MESSAGE_MAX_CHARS);
        try {
            Long lastMessageId = null;
            for (int i = 0; i < parts.size(); i++) {
                String part = parts.get(i);
                Map<String, Object> payload = new HashMap<>();
                payload.put("chat_id", chatId);
                payload.put("text", part);
                if (i == parts.size() - 1 && replyMarkup != null) {
                    payload.put("reply_markup", replyMarkup);
                }

                Map<?, ?> response = telegramRestClient.post()
                        .uri("/bot{token}/sendMessage", properties.botToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .body(Map.class);
                Object result = response == null ? null : response.get("result");
                if (result instanceof Map<?, ?> resultMap) {
                    Object messageId = resultMap.get("message_id");
                    if (messageId instanceof Number n) {
                        lastMessageId = n.longValue();
                    }
                }
            }
            return lastMessageId;
        } catch (RestClientException e) {
            log.error("Failed to send Telegram message (id). chatId={}, error={}", chatId, e.getMessage(), e);
        }
        return null;
    }

    private static List<String> splitTelegramText(String text, int maxChars) {
        if (text == null) {
            return List.of("");
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return List.of(text);
        }

        List<String> parts = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            int end = Math.min(text.length(), index + maxChars);
            end = adjustEndForSurrogatePair(text, index, end);
            int split = findBestSplitIndex(text, index, end);
            if (split <= index) {
                split = end;
            }
            if (split <= index) {
                split = Math.min(text.length(), index + 1);
            }
            parts.add(text.substring(index, split));
            index = split;
        }

        return parts;
    }

    private static int adjustEndForSurrogatePair(String text, int start, int endExclusive) {
        if (endExclusive <= start) {
            return endExclusive;
        }
        if (endExclusive >= text.length()) {
            return endExclusive;
        }
        char last = text.charAt(endExclusive - 1);
        char next = text.charAt(endExclusive);
        if (Character.isHighSurrogate(last) && Character.isLowSurrogate(next)) {
            return endExclusive - 1;
        }
        return endExclusive;
    }

    private static int findBestSplitIndex(String text, int start, int endExclusive) {
        int length = endExclusive - start;
        if (length <= 0) {
            return start;
        }

        int minGoodSplit = start + Math.max(200, (int) (length * 0.6));

        int newline = text.lastIndexOf('\n', endExclusive - 1);
        if (newline >= minGoodSplit) {
            return newline + 1;
        }

        int space = text.lastIndexOf(' ', endExclusive - 1);
        if (space >= minGoodSplit) {
            return space + 1;
        }

        int tab = text.lastIndexOf('\t', endExclusive - 1);
        if (tab >= minGoodSplit) {
            return tab + 1;
        }

        return endExclusive;
    }

    public void registerWebhook(String webhookUrl) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("url", webhookUrl);
        payload.put("secret_token", properties.webhookSecret());
        payload.put("drop_pending_updates", false);

        log.info("Register Telegram webhook url={}", webhookUrl);
        try {
            telegramRestClient.post()
                    .uri("/bot{token}/setWebhook", properties.botToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Telegram webhook registration request sent");
        } catch (RestClientException e) {
            log.error("Failed to register Telegram webhook. error={}", e.getMessage(), e);
            throw e;
        }
    }

    public void logWebhookInfo() {
        try {
            Map<?, ?> response = telegramRestClient.get()
                    .uri("/bot{token}/getWebhookInfo", properties.botToken())
                    .retrieve()
                    .body(Map.class);
            log.info("Telegram getWebhookInfo response={}", Objects.toString(response));
        } catch (RestClientException e) {
            log.error("Failed to fetch getWebhookInfo. error={}", e.getMessage(), e);
        }
    }

    public void configureMiniAppEntryPoints() {
        String miniAppUrl = buildMiniAppUrl();
        if (miniAppUrl == null || miniAppUrl.isBlank()) {
            return;
        }
        try {
            Map<String, Object> descriptionPayload = Map.of(
                    "description", "Мини-приложение календаря: " + miniAppUrl
            );
            telegramRestClient.post()
                    .uri("/bot{token}/setMyDescription", properties.botToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(descriptionPayload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.error("Failed to set bot description with miniapp URL. error={}", e.getMessage());
        }

        if (!isHttpsUrl(miniAppUrl)) {
            return;
        }
        try {
            Map<String, Object> payload = Map.of(
                    "menu_button", Map.of(
                            "type", "web_app",
                            "text", "Календарь",
                            "web_app", Map.of("url", miniAppUrl)
                    )
            );
            telegramRestClient.post()
                    .uri("/bot{token}/setChatMenuButton", properties.botToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.error("Failed to set miniapp chat menu button. error={}", e.getMessage());
        }
    }

    public void deleteWebhook(boolean dropPendingUpdates) {
        Map<String, Object> payload = Map.of("drop_pending_updates", dropPendingUpdates);
        log.info("Delete Telegram webhook requested. dropPendingUpdates={}", dropPendingUpdates);
        try {
            telegramRestClient.post()
                    .uri("/bot{token}/deleteWebhook", properties.botToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Telegram webhook delete request sent");
        } catch (RestClientException e) {
            log.error("Failed to delete Telegram webhook. error={}", e.getMessage(), e);
        }
    }

    private Map<String, Object> buildMainKeyboard(Long chatId) {
        boolean connected = googleOAuthService.isConnected(chatId);
        List<List<Map<String, Object>>> keyboard = new ArrayList<>();
        List<Map<String, Object>> firstRow = new ArrayList<>();
        firstRow.add(Map.of("text", "📅 Сегодня"));
        firstRow.add(Map.of("text", "🗓 Завтра"));
        firstRow.add(Map.of("text", "📆 Неделя"));
        String miniAppUrl = buildMiniAppUrl();
        if (miniAppUrl != null && !miniAppUrl.isBlank()) {
            if (isHttpsUrl(miniAppUrl)) {
                firstRow.add(Map.of(
                        "text", "🗓 Мини‑календарь",
                        "web_app", Map.of("url", miniAppUrl)
                ));
            } else {
                firstRow.add(Map.of("text", "🌐 Ссылка на miniapp"));
            }
        }
        keyboard.add(firstRow);

        List<Map<String, Object>> secondRow = new ArrayList<>();
        secondRow.add(Map.of("text", "📝 Заметки"));
        secondRow.add(Map.of("text", "✏️ Редактировать заметку"));
        secondRow.add(Map.of("text", connected ? "🔗 Переподключить Google" : "🔗 Подключить Google"));
        keyboard.add(secondRow);
        keyboard.add(List.of(Map.of("text", "🗑 Удалить заметку"), Map.of("text", "📎 iCal подписка")));

        return Map.of("resize_keyboard", true, "keyboard", keyboard);
    }

    private Map<String, Object> buildEventCreationKeyboard() {
        return Map.of(
                "resize_keyboard", true,
                "keyboard", List.of(
                        List.of(Map.of("text", "❌ Отмена"))
                )
        );
    }

    private String applyIntent(User user, InboundItem inboundItem, MessageIntent intent) {
        if (intent.action() == BotAction.SHOW_SCHEDULE) {
            return renderSchedule(user, intent.scheduleRange());
        }

        if (intent.action() == BotAction.SHOW_NOTES) {
            return renderNotes(user);
        }

        if (intent.action() == BotAction.CREATE_NOTE) {
            Note note = new Note();
            note.setUser(user);
            note.setTitle(intent.title() == null ? "Заметка" : intent.title());
            note.setContent(intent.noteContent() == null ? "" : intent.noteContent());
            noteRepository.save(note);
            return "📝 Заметка сохранена.\nID: " + note.getId();
        }

        if (intent.action() == BotAction.EDIT_NOTE) {
            if (intent.noteId() == null || intent.noteId().isBlank()) {
                return "Укажите номер заметки для редактирования.";
            }
            Note note = resolveNoteByToken(user, intent.noteId());
            if (note == null) {
                return "Заметка не найдена. Проверьте номер в списке.";
            }
            note.setContent(intent.noteContent() == null ? note.getContent() : intent.noteContent());
            if (intent.noteContent() != null && !intent.noteContent().isBlank()) {
                String newTitle = intent.noteContent().length() > 70 ? intent.noteContent().substring(0, 70) : intent.noteContent();
                note.setTitle(newTitle);
            }
            noteRepository.save(note);
            return "📝 Заметка обновлена: №" + resolveNoteNumber(user, note);
        }

        if (intent.action() == BotAction.DELETE_NOTE) {
            if (intent.noteId() == null || intent.noteId().isBlank()) {
                return "Укажите номер заметки для удаления.";
            }
            Note note = resolveNoteByToken(user, intent.noteId());
            if (note == null) {
                return "Заметка не найдена. Проверьте номер в списке.";
            }
            int noteNumber = resolveNoteNumber(user, note);
            note.setArchived(true);
            noteRepository.save(note);
            return "🗑 Заметка удалена: №" + noteNumber;
        }

        if (intent.classification() == FilterClassification.MEETING && intent.startsAt() != null && intent.endsAt() != null) {
            ZoneId zoneId = resolveZone(user.getTimezone());
            Meeting meeting = createMeetingWithReminder(
                    user,
                    inboundItem,
                    intent.title(),
                    intent.startsAt(),
                    intent.endsAt(),
                    intent.externalLink(),
                    zoneId
            );
            return withLink(intent.responseText(), meeting.getExternalLink()) + buildGoogleSyncWarning(user, meeting);
        }

        if (intent.classification() == FilterClassification.TASK) {
            LocalDate taskDate = intent.dueAt() != null ? intent.dueAt().toLocalDate() : LocalDate.now(resolveZone(user.getTimezone()));
            CalendarDay day = getOrCreateDay(user, taskDate);
            TaskItem taskItem = new TaskItem();
            taskItem.setCalendarDay(day);
            taskItem.setInboundItem(inboundItem);
            taskItem.setTitle(intent.title());
            taskItem.setPriority(intent.priority() == null ? PriorityLevel.MEDIUM : intent.priority());
            taskItem.setDueAt(intent.dueAt());
            taskItemRepository.save(taskItem);
            return intent.responseText();
        }

        if (intent.classification() == FilterClassification.IGNORE) {
            inboundItem.setProcessingStatus(InboundStatus.IGNORED);
            inboundItemRepository.save(inboundItem);
            return intent.responseText();
        }

        if (inboundItem.getRawText() != null && inboundItem.getRawText().toLowerCase().contains("подключить google")) {
            if (googleOAuthService.isConnected(user)) {
                return "Google Calendar уже подключен.";
            }
            return buildGoogleConnectMessage(user);
        }

        return intent.responseText();
    }

    private CalendarDay getOrCreateDay(User user, LocalDate dayDate) {
        return calendarDayRepository.findByUserAndDayDate(user, dayDate)
                .orElseGet(() -> {
                    CalendarDay day = new CalendarDay();
                    day.setUser(user);
                    day.setDayDate(dayDate);
                    day.setBusyLevel(0);
                    return calendarDayRepository.save(day);
                });
    }

    private Meeting createMeetingWithReminder(
            User user,
            InboundItem inboundItem,
            String title,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String externalLink,
            ZoneId zoneId
    ) {
        CalendarDay day = getOrCreateDay(user, startsAt.toLocalDate());
        Meeting meeting = new Meeting();
        meeting.setCalendarDay(day);
        meeting.setInboundItem(inboundItem);
        String cleanedTitle = stripCreateCommandPhrases(title);
        meeting.setTitle(cleanedTitle == null || cleanedTitle.isBlank() ? "Событие" : cleanedTitle);
        meeting.setStartsAt(startsAt);
        meeting.setEndsAt(endsAt);
        meeting.setExternalLink(externalLink);
        meeting.setStatus(MeetingStatus.CONFIRMED);

        GoogleCalendarService.CreatedGoogleEvent googleEvent = googleCalendarService.createEvent(
                user,
                meeting.getTitle(),
                startsAt,
                endsAt,
                externalLink,
                zoneId == null ? DEFAULT_ZONE : zoneId
        );
        if (googleEvent != null) {
            if (googleEvent.eventId() != null && !googleEvent.eventId().isBlank()) {
                meeting.setGoogleEventId(googleEvent.eventId());
            }
            if ((meeting.getExternalLink() == null || meeting.getExternalLink().isBlank())
                    && googleEvent.htmlLink() != null && !googleEvent.htmlLink().isBlank()) {
                meeting.setExternalLink(googleEvent.htmlLink());
            }
        }

        meetingRepository.save(meeting);
        day.setBusyLevel(day.getBusyLevel() + 1);
        calendarDayRepository.save(day);

        scheduleMeetingReminder(user, meeting);
        return meeting;
    }

    private void scheduleMeetingReminder(User user, Meeting meeting) {
        if (user == null || meeting == null || meeting.getStartsAt() == null || meeting.getId() == null) {
            return;
        }
        OffsetDateTime notifyAt = meeting.getStartsAt().minusMinutes(30);
        if (notifyAt.isBefore(OffsetDateTime.now())) {
            return;
        }
        Notification notification = new Notification();
        notification.setUser(user);
        notification.setRelatedType(RelatedType.MEETING);
        notification.setRelatedId(meeting.getId());
        notification.setNotifyAt(notifyAt);
        notification.setSent(false);
        notificationRepository.save(notification);
    }

    private String buildGoogleSyncWarning(User user, Meeting meeting) {
        if (user == null || meeting == null) {
            return "";
        }
        if (!googleCalendarService.isEnabled()) {
            return "";
        }
        if (meeting.getExternalLink() != null && !meeting.getExternalLink().isBlank()) {
            return "";
        }
        return "\n⚠️ Не удалось записать событие в Google Calendar. Проверьте подключение Google и включение Calendar API.";
    }

    private ZoneId resolveZone(String timezone) {
        try {
            return ZoneId.of(timezone == null || timezone.isBlank() ? DEFAULT_ZONE.getId() : timezone);
        } catch (Exception ignored) {
            return DEFAULT_ZONE;
        }
    }

    private String renderSchedule(User user, ScheduleRange requestedRange) {
        ScheduleRange range = requestedRange == null ? ScheduleRange.TODAY : requestedRange;
        ZoneId zoneId = resolveZone(user.getTimezone());
        LocalDate from = LocalDate.now(zoneId);
        LocalDate to = from;
        String label = "сегодня";
        if (range == ScheduleRange.TOMORROW) {
            from = from.plusDays(1);
            to = from;
            label = "завтра";
        } else if (range == ScheduleRange.WEEK) {
            to = from.plusDays(6);
            label = "неделю";
        }

        List<CalendarEventView> events = new ArrayList<>();
        for (Meeting meeting : meetingRepository.findByCalendarDay_UserAndCalendarDay_DayDateBetweenOrderByStartsAtAsc(user, from, to)) {
            events.add(new CalendarEventView(meeting.getTitle(), meeting.getStartsAt(), meeting.getEndsAt(), "internal", meeting.getExternalLink()));
        }
        events.addAll(googleCalendarService.listEvents(user, from, to, zoneId));
        events.sort(Comparator.comparing(CalendarEventView::startsAt));

        List<TaskItem> tasks = taskItemRepository.findByCalendarDay_UserAndCalendarDay_DayDateBetweenOrderByDueAtAsc(user, from, to);

        if (events.isEmpty() && tasks.isEmpty()) {
            return "📭 На " + label + " событий и задач не найдено.";
        }

        StringBuilder sb = new StringBuilder("📅 Расписание на ").append(label).append(":\n");
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("dd.MM HH:mm");
        for (CalendarEventView event : events) {
            sb.append("\n• ").append(event.title())
                    .append(" (").append(event.startsAt().format(timeFmt))
                    .append(" - ").append(event.endsAt().format(timeFmt)).append(")");
            if (event.link() != null && !event.link().isBlank()) {
                sb.append("\n  🔗 ").append(event.link());
            }
        }

        if (!tasks.isEmpty()) {
            sb.append("\n\n✅ Задачи:");
            for (TaskItem task : tasks) {
                sb.append("\n• ").append(task.getTitle());
                if (task.getDueAt() != null) {
                    sb.append(" (до ").append(task.getDueAt().format(timeFmt)).append(")");
                }
            }
        }

        return sb.toString();
    }

    private String renderNotes(User user) {
        List<Note> notes = noteRepository.findTop20ByUserAndArchivedFalseOrderByUpdatedAtDesc(user);
        if (notes.isEmpty()) {
            return "📝 Заметок пока нет.\nСоздайте: заметка: текст";
        }
        StringBuilder sb = new StringBuilder("📝 Ваши заметки:\n");
        for (int i = 0; i < notes.size(); i++) {
            Note note = notes.get(i);
            String num = "[" + (i + 1) + "]";
            String title = truncate(note.getTitle(), 60);
            sb.append("\n").append(num).append(" ").append(title);
        }
        sb.append("\n\nДействия:");
        sb.append("\n✏️ Редактировать: `✏️ <номер> новый текст`");
        sb.append("\n🗑 Удалить: `🗑 <номер>`");
        return sb.toString();
    }

    private Note resolveNoteByToken(User user, String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String trimmed = token.trim();
        if (trimmed.matches("\\d+")) {
            int index = Integer.parseInt(trimmed);
            if (index <= 0) {
                return null;
            }
            List<Note> notes = noteRepository.findTop20ByUserAndArchivedFalseOrderByUpdatedAtDesc(user);
            if (index > notes.size()) {
                return null;
            }
            return notes.get(index - 1);
        }
        try {
            UUID noteId = UUID.fromString(trimmed);
            return noteRepository.findByIdAndUser(noteId, user).orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private int resolveNoteNumber(User user, Note note) {
        List<Note> notes = noteRepository.findTop20ByUserAndArchivedFalseOrderByUpdatedAtDesc(user);
        for (int i = 0; i < notes.size(); i++) {
            if (notes.get(i).getId().equals(note.getId())) {
                return i + 1;
            }
        }
        return -1;
    }

    private String truncate(String text, int max) {
        if (text == null || text.isBlank()) {
            return "-";
        }
        String cleaned = text.replaceAll("\\s+", " ").trim();
        if (cleaned.length() <= max) {
            return cleaned;
        }
        return cleaned.substring(0, Math.max(0, max - 1)) + "…";
    }

    private String withLink(String base, String link) {
        if (link == null || link.isBlank()) {
            return base;
        }
        return base + "\n🔗 " + link;
    }

    private String buildWelcomeMessage(Long chatId) {
        return "AI Chief of Staff включен.\n"
                + "Отправьте текст или голос, и я сам определю: задача, встреча или запрос расписания.\n"
                + "Кнопки — только для просмотра и редактирования: Сегодня, Завтра, Неделя, Заметки.";
    }

    private InboundItem saveInboundItem(
            User user,
            SourceType sourceType,
            String rawText,
            String fileUrl,
            Map<String, Object> metadata,
            FilterClassification classification,
            InboundStatus status
    ) {
        InboundItem item = new InboundItem();
        item.setUser(user);
        item.setSourceType(sourceType);
        item.setRawText(rawText);
        item.setFileUrl(fileUrl);
        item.setMetadata(metadata == null ? new HashMap<>() : new HashMap<>(metadata));
        item.setFilterClassification(classification);
        item.setProcessingStatus(status == null ? InboundStatus.RECEIVED : status);
        return inboundItemRepository.save(item);
    }

    private boolean isCancelRequest(String text) {
        if (text == null) {
            return false;
        }
        if (text.contains("❌")) {
            return true;
        }
        String normalized = normalizeCommandText(text);
        return normalized.equals("/cancel")
                || normalized.equals("отмена")
                || normalized.contains("отменить")
                || normalized.contains("cancel");
    }

    private boolean shouldStartEventWizard(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = normalizeCommandText(text);
        if (normalized.isBlank()) {
            return false;
        }
        return normalized.equals("создать событие")
                || normalized.equals("создать события")
                || normalized.equals("создай событие")
                || normalized.equals("создай события")
                || normalized.equals("добавить событие")
                || normalized.equals("добавить события")
                || normalized.equals("добавь событие")
                || normalized.equals("добавь события")
                || normalized.equals("новое событие")
                || normalized.equals("создать встречу")
                || normalized.equals("создай встречу")
                || normalized.startsWith("создать событие ")
                || normalized.startsWith("создать события ")
                || normalized.startsWith("создай событие ")
                || normalized.startsWith("создай события ")
                || normalized.startsWith("добавить событие ")
                || normalized.startsWith("добавить события ")
                || normalized.startsWith("добавь событие ")
                || normalized.startsWith("добавь события ")
                || normalized.startsWith("создать встречу ")
                || normalized.startsWith("создай встречу ")
                || EVENT_WIZARD_TRIGGER_PATTERN.matcher(normalized).find();
    }

    private boolean isStartNoteEditFlow(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = normalizeCommandText(text);
        return normalized.equals("редактировать заметку")
                || normalized.equals("✏️ редактировать заметку")
                || normalized.equals("редактировать")
                || normalized.equals("✏️");
    }

    private boolean isStartNoteDeleteFlow(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = normalizeCommandText(text);
        return normalized.equals("удалить заметку")
                || normalized.equals("🗑 удалить заметку")
                || normalized.equals("удалить")
                || normalized.equals("🗑");
    }

    private boolean isIcalSubscriptionRequest(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = normalizeCommandText(text);
        return normalized.equals("📎 ical подписка")
                || normalized.equals("ical подписка")
                || normalized.equals("подписка ical")
                || normalized.equals("ical")
                || normalized.equals("icalendar");
    }

    private boolean isMiniAppLinkRequest(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = normalizeCommandText(text);
        return normalized.equals("🌐 ссылка на miniapp")
                || normalized.equals("ссылка на miniapp")
                || normalized.equals("miniapp")
                || normalized.equals("mini app")
                || normalized.equals("мини апп")
                || normalized.equals("миниапп")
                || normalized.equals("ссылка на миниапп")
                || normalized.equals("miniapp link");
    }

    private WizardResult processNoteEditStep(User user, NoteEditSession session, String text) {
        String input = text == null ? "" : text.trim();
        if (input.isBlank()) {
            return new WizardResult("Пустой ответ. Отправьте номер заметки или нажмите ❌ Отмена.", false);
        }

        if (session.getStep() == null) {
            session.setStep(NoteEditStep.WAIT_NOTE_NUMBER);
        }

        if (session.getStep() == NoteEditStep.WAIT_NOTE_NUMBER) {
            String token = input.replaceAll("[^0-9]", "");
            if (token.isBlank()) {
                return new WizardResult("Нужен номер заметки, например: 3", false);
            }
            Note note = resolveNoteByToken(user, token);
            if (note == null) {
                return new WizardResult("Заметка с таким номером не найдена. Откройте 📝 Заметки и отправьте номер.", false);
            }
            NoteEditMode mode = session.getMode() == null ? NoteEditMode.EDIT : session.getMode();
            if (mode == NoteEditMode.DELETE) {
                int noteNumber = resolveNoteNumber(user, note);
                note.setArchived(true);
                noteRepository.save(note);
                noteEditSessionRepository.delete(session);
                return new WizardResult("🗑 Заметка удалена: №" + noteNumber, true);
            }
            session.setTargetNoteId(note.getId());
            int noteNumber = resolveNoteNumber(user, note);
            session.setTargetNoteNumber(noteNumber > 0 ? noteNumber : null);
            session.setStep(NoteEditStep.WAIT_NEW_TEXT);
            noteEditSessionRepository.save(session);
            return new WizardResult("Шаг 2/2: отправьте новый текст для заметки №" + noteNumber + ".", false);
        }

        if (session.getStep() == NoteEditStep.WAIT_NEW_TEXT) {
            if (session.getTargetNoteId() == null) {
                session.setStep(NoteEditStep.WAIT_NOTE_NUMBER);
                noteEditSessionRepository.save(session);
                return new WizardResult("Потерял номер заметки. Отправьте номер ещё раз.", false);
            }
            Note note = noteRepository.findByIdAndUser(session.getTargetNoteId(), user).orElse(null);
            if (note == null || note.isArchived()) {
                noteEditSessionRepository.delete(session);
                return new WizardResult("Заметка не найдена. Запустите редактирование заново.", true);
            }
            note.setContent(input);
            String newTitle = input.length() > 70 ? input.substring(0, 70) : input;
            note.setTitle(newTitle);
            noteRepository.save(note);
            Integer number = session.getTargetNoteNumber();
            noteEditSessionRepository.delete(session);
            return new WizardResult("📝 Заметка обновлена: №" + (number == null ? "?" : number), true);
        }

        session.setStep(NoteEditStep.WAIT_NOTE_NUMBER);
        noteEditSessionRepository.save(session);
        return new WizardResult("Отправьте номер заметки из списка.", false);
    }

    private String sanitizeRecognizedText(String text) {
        if (text == null) {
            return null;
        }
        String compact = text
                .replace('\u00A0', ' ')
                .replace("\r", " ")
                .replace("\n", " ")
                .replace('\u2013', '-')
                .replace('\u2014', '-')
                .replace('\u2212', '-')
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .replace('\u201C', '"')
                .replace('\u201D', '"')
                .replace('ё', 'е')
                .replace('Ё', 'Е')
                .trim()
                .replaceAll("\\s+", " ");

        compact = compact.replaceAll("(?iu)^знай\\s+сам\\b", "создай");
        compact = compact.replaceAll("(?iu)^зай\\s+сам\\b", "создай");
        compact = compact.replaceAll("^[\\p{Punct}\\s]+", "").replaceAll("[\\p{Punct}\\s]+$", "");
        return compact.isBlank() ? text.trim() : compact;
    }

    private String normalizeCommandText(String text) {
        if (text == null) {
            return "";
        }
        return text
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}/]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void fillEventSessionFromInput(EventCreationSession session, String text, ZoneId zoneId, boolean extractTitleFromCommand) {
        if (session == null || text == null || text.isBlank()) {
            return;
        }
        OllamaStructuredParsingService.ParsedEventData llmParsed = ollamaStructuredParsingService.extractEventData(text, zoneId);
        if (session.getMeetingDate() == null && llmParsed.date() != null) {
            session.setMeetingDate(llmParsed.date());
        }
        if (session.getMeetingTime() == null && llmParsed.time() != null) {
            session.setMeetingTime(llmParsed.time());
        }
        if (session.getDurationMinutes() == null && llmParsed.durationMinutes() != null && llmParsed.durationMinutes() > 0) {
            session.setDurationMinutes(llmParsed.durationMinutes());
        }
        if ((session.getMeetingTitle() == null || session.getMeetingTitle().isBlank())
                && llmParsed.title() != null && !llmParsed.title().isBlank()) {
            String llmTitle = extractTitleFromCommand(llmParsed.title());
            if (llmTitle == null || llmTitle.isBlank()) {
                llmTitle = stripCreateCommandPhrases(llmParsed.title());
            }
            if (llmTitle != null && !llmTitle.isBlank()) {
                llmTitle = llmTitle.length() > 180 ? llmTitle.substring(0, 180) : llmTitle;
                session.setMeetingTitle(llmTitle);
            }
        }
        if (session.getMeetingDate() == null) {
            LocalDate date = parseDate(text, zoneId);
            if (date != null) {
                session.setMeetingDate(date);
            }
        }
        if (session.getMeetingTime() == null) {
            LocalTime time = parseTime(text);
            if (time != null) {
                session.setMeetingTime(time);
            }
        }
        if (session.getDurationMinutes() == null) {
            Integer duration = parseDurationMinutes(text);
            if (duration != null) {
                session.setDurationMinutes(duration);
            }
        }
        if ((session.getMeetingTitle() == null || session.getMeetingTitle().isBlank()) && extractTitleFromCommand) {
            String title = extractTitleFromCommand(text);
            if (title != null) {
                session.setMeetingTitle(title);
            }
        }
    }

    private EventCreationStep nextMissingStep(EventCreationSession session) {
        if (session.getMeetingDate() == null) {
            return EventCreationStep.WAIT_DATE;
        }
        if (session.getMeetingTime() == null) {
            return EventCreationStep.WAIT_TIME;
        }
        if (session.getMeetingTitle() == null || session.getMeetingTitle().isBlank()) {
            return EventCreationStep.WAIT_TITLE;
        }
        if (session.getDurationMinutes() == null) {
            return EventCreationStep.WAIT_DURATION;
        }
        return null;
    }

    private String extractTitleFromCommand(String text) {
        String cleaned = text == null ? "" : text;
        cleaned = cleaned.replaceAll("(?iu)\\b(созда(ть|й)|добав(ить|ь)|запланиру(й|йте|ю)|сдела(й|ть))\\b", " ");
        cleaned = cleaned.replaceAll("(?iu)^\\s*событи[еяю]\\b", " ");
        cleaned = cleaned.replaceAll("(?iu)\\b(сегодня|завтра|послезавтра|на|в)\\b", " ");
        cleaned = cleaned.replaceAll("(?iu)\\b\\d{1,2}[:.]\\d{2}\\b", " ");
        cleaned = cleaned.replaceAll("(?iu)\\b\\d{1,2}\\s*(час|часа|часов)\\b", " ");
        cleaned = cleaned.replaceAll("(?iu)\\b[а-яё\\-]+(?:\\s+[а-яё\\-]+)?\\s+час(?:а|ов)?\\b(?:\\s+(утра|дня|вечера|ночи))?", " ");
        cleaned = cleaned.replaceAll("(?iu)\\bв\\s+[а-яё\\-]+(?:\\s+[а-яё\\-]+)?\\s+(утра|дня|вечера|ночи)\\b", " ");
        cleaned = cleaned.replaceAll("(?iu)\\bв\\s+\\d{1,2}\\b(?:\\s*(утра|дня|вечера|ночи))?", " ");
        cleaned = cleaned.replaceAll("(?iu)\\b\\d{1,3}\\s*мин(?:ут[аы]?)?\\b", " ");
        cleaned = cleaned.replaceAll("(?iu)\\bдлительност\\p{L}*\\s+[а-яё0-9\\s.,\\-]+$", " ");
        cleaned = cleaned.replaceAll("(?iu)\\bдлительност\\p{L}*\\b", " ");
        cleaned = cleaned.replaceAll("(?iu)\\b\\d{1,2}[./]\\d{1,2}(?:[./]\\d{2,4})?\\b", " ");
        cleaned = cleaned.replaceAll("(?iu)\\b\\d{1,2}\\s+(январ[яе]|феврал[яе]|март[а]?|апрел[яе]|ма[йя]|июн[яе]|июл[яе]|август[а]?|сентябр[яе]|октябр[яе]|ноябр[яе]|декабр[яе])(?:\\s+\\d{4})?\\b", " ");
        cleaned = cleaned.replaceAll("(?iu)\\b[а-яё\\-]+(?:\\s+[а-яё\\-]+)?\\s+(январ[яе]|феврал[яе]|март[а]?|апрел[яе]|ма[йя]|июн[яе]|июл[яе]|август[а]?|сентябр[яе]|октябр[яе]|ноябр[яе]|декабр[яе])(?:\\s+[а-яё\\s\\-]+\\s+г(?:ода|од)?)?\\b", " ");
        cleaned = cleaned.replaceAll("(?iu)\\bдве\\s+тысячи\\s+[а-яё\\-]+\\b", " ");
        cleaned = cleaned.replaceAll("(?iu)\\b(год|года)\\b", " ");
        cleaned = cleaned.replaceAll("(?iu)\\b(утра|дня|вечера|ночи)\\b", " ");
        cleaned = stripCreateCommandPhrases(cleaned);
        cleaned = cutAtTemporalTail(cleaned);
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        if (cleaned.isBlank()) {
            return null;
        }
        return cleaned.length() > 180 ? cleaned.substring(0, 180) : cleaned;
    }

    private String stripCreateCommandPhrases(String source) {
        if (source == null) {
            return "";
        }
        String cleaned = source.replace('\u00A0', ' ').trim();
        Pattern leadingCommand = Pattern.compile(
                "(?iu)^\\s*(?:ну\\s+)?(?:пожалуйста\\s+)?(?:созда(й|ть)|добав(ь|ить)|запланиру(й|йте|ю)|сдела(й|ть))\\s+(?:мне\\s+)?(?:событи\\p{L}*|встреч\\p{L}*)\\s*");
        Matcher matcher = leadingCommand.matcher(cleaned);
        while (matcher.find()) {
            cleaned = cleaned.substring(matcher.end()).trim();
            matcher = leadingCommand.matcher(cleaned);
        }
        cleaned = cleaned.replaceAll(
                "(?iu)\\b(?:созда(й|ть)|добав(ь|ить)|запланиру(й|йте|ю)|сдела(й|ть))\\s+(?:мне\\s+)?(?:событи\\p{L}*|встреч\\p{L}*)\\b",
                " ");
        cleaned = cleaned.replaceAll("(?iu)^\\s*(созда(й|ть)|добав(ь|ить)|запланиру(й|йте|ю)|сдела(й|ть))\\b\\s*", " ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned;
    }

    private String cutAtTemporalTail(String source) {
        if (source == null || source.isBlank()) {
            return source == null ? "" : source;
        }
        int cut = source.length();
        cut = Math.min(cut, firstMatchIndex(source, "(?iu)\\bдлительност\\p{L}*\\b"));
        cut = Math.min(cut, firstMatchIndex(source, "(?iu)\\b\\d{1,2}[./]\\d{1,2}(?:[./]\\d{2,4})?\\b"));
        cut = Math.min(cut, firstMatchIndex(source, "(?iu)\\b\\d{1,2}\\s+(январ[яе]|феврал[яе]|март[а]?|апрел[яе]|ма[йя]|июн[яе]|июл[яе]|август[а]?|сентябр[яе]|октябр[яе]|ноябр[яе]|декабр[яе])\\b"));
        cut = Math.min(cut, firstMatchIndex(source, "(?iu)\\b[а-яё\\-]+(?:\\s+[а-яё\\-]+)?\\s+(январ[яе]|феврал[яе]|март[а]?|апрел[яе]|ма[йя]|июн[яе]|июл[яе]|август[а]?|сентябр[яе]|октябр[яе]|ноябр[яе]|декабр[яе])\\b"));
        cut = Math.min(cut, firstMatchIndex(source, "(?iu)\\bв\\s+\\d{1,2}(?::\\d{2})?\\b"));
        cut = Math.min(cut, firstMatchIndex(source, "(?iu)\\bв\\s+[а-яё\\-]+(?:\\s+[а-яё\\-]+)?\\s+(утра|дня|вечера|ночи)\\b"));
        if (cut <= 0 || cut >= source.length()) {
            return source;
        }
        return source.substring(0, cut).trim();
    }

    private int firstMatchIndex(String source, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(source);
        return matcher.find() ? matcher.start() : source.length();
    }

    private WizardResult processEventWizardStep(User user, EventCreationSession session, String text, ZoneId zoneId) {
        if (session.getStep() == null) {
            session.setStep(EventCreationStep.WAIT_DATE);
        }

        String input = text == null ? "" : text.trim();
        if (input.isBlank()) {
            return new WizardResult("Я не вижу ответа. Напишите текстом или нажмите ❌ Отмена.", false);
        }
        fillEventSessionFromInput(session, input, zoneId, shouldStartEventWizard(input));
        if (session.getStep() == EventCreationStep.WAIT_TITLE
                && (session.getMeetingTitle() == null || session.getMeetingTitle().isBlank())) {
            String title = extractTitleFromCommand(input);
            if (title == null || title.isBlank()) {
                title = input.length() > 180 ? input.substring(0, 180) : input;
            }
            session.setMeetingTitle(title);
        }
        if (session.getStep() == EventCreationStep.WAIT_DURATION && session.getDurationMinutes() == null) {
            Integer duration = parseDurationMinutes(input);
            if (duration != null) {
                session.setDurationMinutes(duration);
            }
        }

        EventCreationStep missing = nextMissingStep(session);
        if (missing == null) {
            OffsetDateTime startsAt = session.getMeetingDate()
                    .atTime(session.getMeetingTime())
                    .atZone(zoneId == null ? DEFAULT_ZONE : zoneId)
                    .toOffsetDateTime();
            OffsetDateTime endsAt = startsAt.plusMinutes(session.getDurationMinutes());

            Meeting meeting = createMeetingWithReminder(
                    user,
                    null,
                    session.getMeetingTitle() == null || session.getMeetingTitle().isBlank() ? "Событие" : session.getMeetingTitle(),
                    startsAt,
                    endsAt,
                    null,
                    zoneId
            );
            eventCreationSessionRepository.delete(session);
            String msg = "✅ Событие создано: " + meeting.getTitle() + "\n🕒 "
                    + startsAt.toLocalDate() + " " + startsAt.toLocalTime().withSecond(0).withNano(0);
            return new WizardResult(msg + buildGoogleSyncWarning(user, meeting), true);
        }

        session.setStep(missing);
        eventCreationSessionRepository.save(session);
        return new WizardResult(promptForStep(missing), false);
    }

    private String promptForStep(EventCreationStep step) {
        return switch (step) {
            case WAIT_DATE -> "Не распознал дату. Напишите только дату: 21.02.2026 или 21 февраля.";
            case WAIT_TIME -> "Не распознал время. Напишите только время: 14:30 или в 14 часов.";
            case WAIT_TITLE -> "Как назвать событие? Напишите только название.";
            case WAIT_DURATION -> "Не распознал длительность. Напишите только длительность: 30 минут, 1 час, 1.5 часа.";
        };
    }

    private LocalDate parseDate(String text, ZoneId zoneId) {
        String normalized = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);

        Matcher m1 = DATE_PATTERN.matcher(normalized);
        if (m1.find()) {
            int day = Integer.parseInt(m1.group(1));
            int month = Integer.parseInt(m1.group(2));
            Integer year = null;
            if (m1.group(3) != null) {
                year = Integer.parseInt(m1.group(3));
                if (year < 100) {
                    year = 2000 + year;
                }
            }
            int resolvedYear = year != null ? year : LocalDate.now(zoneId == null ? DEFAULT_ZONE : zoneId).getYear();
            try {
                LocalDate candidate = LocalDate.of(resolvedYear, month, day);
                if (year == null) {
                    LocalDate today = LocalDate.now(zoneId == null ? DEFAULT_ZONE : zoneId);
                    if (candidate.isBefore(today.minusDays(1))) {
                        candidate = candidate.plusYears(1);
                    }
                }
                return candidate;
            } catch (Exception ignored) {
                return null;
            }
        }

        Matcher m2 = DATE_TEXT_PATTERN.matcher(normalized);
        if (m2.find()) {
            int day = Integer.parseInt(m2.group(1));
            String monthText = m2.group(2);
            Integer month = resolveRuMonth(monthText);
            if (month == null) {
                return null;
            }
            Integer year = null;
            if (m2.group(3) != null) {
                year = Integer.parseInt(m2.group(3));
            }
            int resolvedYear = year != null ? year : LocalDate.now(zoneId == null ? DEFAULT_ZONE : zoneId).getYear();
            try {
                LocalDate candidate = LocalDate.of(resolvedYear, month, day);
                if (year == null) {
                    LocalDate today = LocalDate.now(zoneId == null ? DEFAULT_ZONE : zoneId);
                    if (candidate.isBefore(today.minusDays(1))) {
                        candidate = candidate.plusYears(1);
                    }
                }
                return candidate;
            } catch (Exception ignored) {
                return null;
            }
        }

        Matcher m3 = DATE_WORDS_PATTERN.matcher(normalized);
        if (m3.find()) {
            Integer day = RU_DAY_WORDS.get(m3.group(1).trim());
            Integer month = resolveRuMonth(m3.group(2));
            if (day == null || month == null) {
                return null;
            }
            int resolvedYear = LocalDate.now(zoneId == null ? DEFAULT_ZONE : zoneId).getYear();
            Integer parsedYear = parseRussianWordsNumber(m3.group(3));
            if (parsedYear != null && parsedYear >= 1900 && parsedYear <= 2200) {
                resolvedYear = parsedYear;
            }
            try {
                return LocalDate.of(resolvedYear, month, day);
            } catch (Exception ignored) {
                return null;
            }
        }

        return null;
    }

    private Integer resolveRuMonth(String monthText) {
        if (monthText == null) {
            return null;
        }
        String key = monthText.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Integer> entry : RUS_MONTHS.entrySet()) {
            if (key.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private LocalTime parseTime(String text) {
        String normalized = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        String withoutDates = normalized.replaceAll("\\b\\d{1,2}[.]\\d{1,2}(?:[.]\\d{2,4})?\\b", " ");
        Matcher m1 = TIME_COLON_PATTERN.matcher(withoutDates);
        if (m1.find()) {
            int hour = Integer.parseInt(m1.group(1));
            int minute = Integer.parseInt(m1.group(2));
            try {
                return LocalTime.of(hour, minute);
            } catch (Exception ignored) {
                return null;
            }
        }
        Matcher m2 = TIME_HOUR_ONLY_PATTERN.matcher(withoutDates);
        if (m2.find()) {
            int hour = Integer.parseInt(m2.group(1));
            try {
                return LocalTime.of(hour, 0);
            } catch (Exception ignored) {
                return null;
            }
        }
        Matcher m3 = TIME_HOUR_WORDS_PATTERN.matcher(withoutDates);
        if (m3.find()) {
            Integer hour = parseRussianWordsNumber(m3.group(1));
            if (hour == null || hour < 0 || hour > 23) {
                return null;
            }
            return LocalTime.of(hour, 0);
        }
        return null;
    }

    private Integer parseDurationMinutes(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.equals("пропустить") || normalized.equals("skip")) {
            return 60;
        }

        Matcher mMin = DURATION_MIN_PATTERN.matcher(normalized);
        if (mMin.find()) {
            int minutes = Integer.parseInt(mMin.group(1));
            return minutes > 0 ? minutes : null;
        }

        Matcher mDec = DURATION_HOUR_DECIMAL_PATTERN.matcher(normalized);
        if (mDec.find()) {
            int hours = Integer.parseInt(mDec.group(1));
            int tenth = Integer.parseInt(mDec.group(2));
            int minutes = hours * 60 + (int) Math.round(tenth * 6.0);
            return minutes > 0 ? minutes : null;
        }

        Matcher mHour = DURATION_HOUR_PATTERN.matcher(normalized);
        if (mHour.find()) {
            int hours = Integer.parseInt(mHour.group(1));
            int minutes = hours * 60;
            return minutes > 0 ? minutes : null;
        }

        if (normalized.equals("час") || normalized.equals("1 час") || normalized.equals("один час")) {
            return 60;
        }

        return null;
    }

    private Integer parseRussianWordsNumber(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        String normalized = source.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.matches("\\d{1,4}")) {
            try {
                return Integer.parseInt(normalized);
            } catch (Exception ignored) {
                return null;
            }
        }
        String[] tokens = normalized.split(" ");
        int total = 0;
        int current = 0;
        boolean hasAny = false;
        for (String token : tokens) {
            Integer value = RU_NUMBER_WORDS.get(token);
            if (value == null) {
                continue;
            }
            hasAny = true;
            if (value == 1000) {
                if (current == 0) {
                    current = 1;
                }
                total += current * 1000;
                current = 0;
            } else if (value == 100) {
                if (current == 0) {
                    current = 100;
                } else {
                    current *= 100;
                }
            } else {
                current += value;
            }
        }
        if (!hasAny) {
            return null;
        }
        return total + current;
    }

    private static Map<String, Integer> buildRuDayWords() {
        Map<String, Integer> map = new HashMap<>();
        map.put("первого", 1);
        map.put("второго", 2);
        map.put("третьего", 3);
        map.put("четвертого", 4);
        map.put("пятого", 5);
        map.put("шестого", 6);
        map.put("седьмого", 7);
        map.put("восьмого", 8);
        map.put("девятого", 9);
        map.put("десятого", 10);
        map.put("одиннадцатого", 11);
        map.put("двенадцатого", 12);
        map.put("тринадцатого", 13);
        map.put("четырнадцатого", 14);
        map.put("пятнадцатого", 15);
        map.put("шестнадцатого", 16);
        map.put("семнадцатого", 17);
        map.put("восемнадцатого", 18);
        map.put("девятнадцатого", 19);
        map.put("двадцатого", 20);
        map.put("двадцать первого", 21);
        map.put("двадцать второго", 22);
        map.put("двадцать третьего", 23);
        map.put("двадцать четвертого", 24);
        map.put("двадцать пятого", 25);
        map.put("двадцать шестого", 26);
        map.put("двадцать седьмого", 27);
        map.put("двадцать восьмого", 28);
        map.put("двадцать девятого", 29);
        map.put("тридцатого", 30);
        map.put("тридцать первого", 31);
        return map;
    }

    private static Map<String, Integer> buildRuNumberWords() {
        Map<String, Integer> map = new HashMap<>();
        map.put("ноль", 0);
        map.put("один", 1);
        map.put("одна", 1);
        map.put("первого", 1);
        map.put("два", 2);
        map.put("две", 2);
        map.put("второго", 2);
        map.put("три", 3);
        map.put("третьего", 3);
        map.put("четыре", 4);
        map.put("четвертого", 4);
        map.put("пять", 5);
        map.put("пятого", 5);
        map.put("шесть", 6);
        map.put("шестого", 6);
        map.put("семь", 7);
        map.put("седьмого", 7);
        map.put("восемь", 8);
        map.put("восьмого", 8);
        map.put("девять", 9);
        map.put("девятого", 9);
        map.put("десять", 10);
        map.put("десятого", 10);
        map.put("одиннадцать", 11);
        map.put("одиннадцатого", 11);
        map.put("двенадцать", 12);
        map.put("двенадцатого", 12);
        map.put("тринадцать", 13);
        map.put("тринадцатого", 13);
        map.put("четырнадцать", 14);
        map.put("четырнадцатого", 14);
        map.put("пятнадцать", 15);
        map.put("пятнадцатого", 15);
        map.put("шестнадцать", 16);
        map.put("шестнадцатого", 16);
        map.put("семнадцать", 17);
        map.put("семнадцатого", 17);
        map.put("восемнадцать", 18);
        map.put("восемнадцатого", 18);
        map.put("девятнадцать", 19);
        map.put("девятнадцатого", 19);
        map.put("двадцать", 20);
        map.put("двадцатого", 20);
        map.put("тридцать", 30);
        map.put("тридцатого", 30);
        map.put("сорок", 40);
        map.put("пятьдесят", 50);
        map.put("шестьдесят", 60);
        map.put("семьдесят", 70);
        map.put("восемьдесят", 80);
        map.put("девяносто", 90);
        map.put("сто", 100);
        map.put("тысяча", 1000);
        map.put("тысячи", 1000);
        map.put("тысяч", 1000);
        return map;
    }

    private record WizardResult(String message, boolean showMainKeyboard) {
    }

    private String buildGoogleConnectMessage(User user) {
        return buildGoogleConnectMessage(user.getTelegramId());
    }

    private String buildGoogleConnectMessage(Long telegramId) {
        return googleOAuthService.createConnectUrl(telegramId)
                .map(url -> "🔗 Подключить Google Calendar:\n" + url)
                .orElse("Google OAuth не настроен. Укажите APP_PUBLIC_BASE_URL и Google OAuth env.");
    }

    private void sendStartFlow(Long chatId) {
        sendMessage(chatId, buildWelcomeMessage(chatId), true);

        sendPinnedMiniAppLink(chatId);

        String loginUrl = googleOAuthService.createConnectUrl(chatId).orElse(null);
        if (loginUrl != null && !loginUrl.isBlank()) {
            String text = googleOAuthService.isConnected(chatId)
                    ? "Нажмите, чтобы переподключить Google Calendar:"
                    : "Нажмите, чтобы войти в Google и синхронизировать календарь:";
            sendInlineGoogleConnectButton(chatId, loginUrl, text);
        } else {
            sendMessage(chatId,
                    "Для входа через Google нужен публичный URL приложения. " +
                            "Если тест локально на этом же ноутбуке: APP_PUBLIC_BASE_URL=http://localhost:8010",
                    false);
        }
    }

    private void sendPinnedMiniAppLink(Long chatId) {
        String miniAppUrl = buildMiniAppUrl();
        if (miniAppUrl == null || miniAppUrl.isBlank() || !isHttpsUrl(miniAppUrl)) {
            log.info("Skip pin miniapp link: url is missing or not https. url={}", miniAppUrl);
            return;
        }
        Map<String, Object> inlineMarkup = Map.of(
                "inline_keyboard", List.of(
                        List.of(Map.of("text", "Открыть календарь", "web_app", Map.of("url", miniAppUrl)))
                )
        );
        Long messageId = sendMessageAndGetId(chatId, "Календарь (Mini App):", inlineMarkup);
        if (messageId == null) {
            return;
        }
        try {
            Map<String, Object> payload = Map.of(
                    "chat_id", chatId,
                    "message_id", messageId,
                    "disable_notification", true
            );
            telegramRestClient.post()
                    .uri("/bot{token}/pinChatMessage", properties.botToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.error("Failed to pin miniapp message. chatId={}, error={}", chatId, e.getMessage(), e);
        }
    }

    private String buildMiniAppUrl() {
        String baseUrl = miniAppPublicUrl;
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (scheme == null || host == null) {
                return null;
            }
            StringBuilder origin = new StringBuilder(scheme).append("://").append(host);
            if (port > 0) {
                origin.append(":").append(port);
            }
            String path = uri.getPath();
            if (path != null && !path.isBlank() && !"/".equals(path)) {
                return origin + path;
            }
            return origin + "/miniapp";
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isHttpsUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            return "https".equalsIgnoreCase(uri.getScheme());
        } catch (Exception e) {
            return false;
        }
    }
    private void sendInlineGoogleConnectButton(Long chatId, String loginUrl, String text) {
        Map<String, Object> inlineMarkup = Map.of(
                "inline_keyboard", List.of(
                        List.of(Map.of("text", "Войти в Google", "url", loginUrl))
                )
        );
        Map<String, Object> payload = new HashMap<>();
        payload.put("chat_id", chatId);
        payload.put("text", text);
        payload.put("reply_markup", inlineMarkup);
        try {
            telegramRestClient.post()
                    .uri("/bot{token}/sendMessage", properties.botToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.error("Failed to send Google inline button. chatId={}, error={}", chatId, e.getMessage(), e);
            sendMessage(chatId, "Не удалось отправить кнопку входа в Google.", false);
        }
    }

    private String buildVoiceFailureMessage(Exception error) {
        String message = error == null ? "" : Objects.toString(error.getMessage(), "").toLowerCase();
        if (message.contains("checksum") || message.contains("whisper model download")) {
            return "Не удалось распознать голос: локальная модель Whisper не скачалась корректно. "
                    + "Проверьте сеть к openaipublic.azureedge.net или задайте локальный файл через APP_WHISPER_MODEL.";
        }
        if (message.contains("vosk stt failed")) {
            return "Не удалось распознать голос через Vosk. Проверьте APP_VOSK_MODEL_PATH и модель.";
        }
        return "Не удалось распознать голос. Проверьте локальные движки STT (Vosk/Whisper) и попробуйте еще раз.";
    }
}

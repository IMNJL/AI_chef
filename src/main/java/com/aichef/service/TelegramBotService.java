package com.aichef.service;

import com.aichef.config.TelegramProperties;
import com.aichef.domain.enums.FilterClassification;
import com.aichef.domain.enums.InboundStatus;
import com.aichef.domain.enums.MeetingStatus;
import com.aichef.domain.enums.PriorityLevel;
import com.aichef.domain.enums.SourceType;
import com.aichef.domain.model.CalendarDay;
import com.aichef.domain.model.EventCreationSession;
import com.aichef.domain.model.InboundItem;
import com.aichef.domain.model.Meeting;
import com.aichef.domain.model.Note;
import com.aichef.domain.model.TaskItem;
import com.aichef.domain.model.User;
import com.aichef.dto.TelegramWebhookUpdate;
import com.aichef.repository.CalendarDayRepository;
import com.aichef.repository.EventCreationSessionRepository;
import com.aichef.repository.InboundItemRepository;
import com.aichef.repository.MeetingRepository;
import com.aichef.repository.NoteRepository;
import com.aichef.repository.TaskItemRepository;
import com.aichef.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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

    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?");
    private static final Pattern DATE_TEXT_PATTERN = Pattern.compile(
            "\\b(\\d{1,2})\\s+(январ[яе]|феврал[яе]|март[а]?|апрел[яе]|ма[йя]|июн[яе]|июл[яе]|август[а]?|сентябр[яе]|октябр[яе]|ноябр[яе]|декабр[яе])(?:\\s+(\\d{4}))?\\b");
    private static final Pattern TIME_COLON_PATTERN = Pattern.compile("\\b(?:в|на)?\\s*(\\d{1,2})[:.](\\d{2})\\b");
    private static final Pattern TIME_HOUR_ONLY_PATTERN = Pattern.compile("\\b(?:в|на)?\\s*(\\d{1,2})\\s*(?:час|часа|часов)\\b");
    private static final Pattern DURATION_MIN_PATTERN = Pattern.compile("\\b(\\d{1,3})\\s*мин(?:ут[аы]?)?\\b");
    private static final Pattern DURATION_HOUR_DECIMAL_PATTERN = Pattern.compile("\\b(\\d+)[,.](\\d)\\s*час");
    private static final Pattern DURATION_HOUR_PATTERN = Pattern.compile("\\b(\\d{1,2})\\s*час(?:а|ов)?\\b");
    private static final Pattern EVENT_WIZARD_TRIGGER_PATTERN = Pattern.compile(
            "\\b(созда(ть|й)|добав(ить|ь)|запланиру(й|йте|ю)|сдела(й|ть))\\s+(событи[еяю]|встреч[ауеи])\\b");
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

    private final RestClient telegramRestClient;
    private final TelegramProperties properties;
    private final UserRepository userRepository;
    private final InboundItemRepository inboundItemRepository;
    private final EventCreationSessionRepository eventCreationSessionRepository;
    private final CalendarDayRepository calendarDayRepository;
    private final MeetingRepository meetingRepository;
    private final TaskItemRepository taskItemRepository;
    private final NoteRepository noteRepository;
    private final MessageUnderstandingService messageUnderstandingService;
    private final VoiceTranscriptionService voiceTranscriptionService;
    private final GoogleCalendarService googleCalendarService;
    private final GoogleOAuthService googleOAuthService;

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
            sendMessage(chatId, wizardResult.message(), wizardResult.showMainKeyboard() ? buildMainKeyboard() : buildEventCreationKeyboard());
            return;
        }

        if (shouldStartEventWizard(rawText)) {
            EventCreationSession newSession = new EventCreationSession();
            newSession.setUser(user);
            newSession.setStep(EventCreationStep.WAIT_DATE);
            eventCreationSessionRepository.save(newSession);
            saveInboundItem(user, sourceType, rawText, fileUrl, metadata,
                    FilterClassification.ASK_CLARIFICATION, InboundStatus.NEEDS_CLARIFICATION);
            sendMessage(chatId,
                    "Начинаем создание события.\nШаг 1/4: на какую дату поставить событие? (например: 21.02.2026 или 21 февраля)",
                    buildEventCreationKeyboard());
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
        sendMessage(chatId, text, withKeyboard ? buildMainKeyboard() : null);
    }

    private void sendMessage(Long chatId, String text, Map<String, Object> replyMarkup) {
        log.info("Send Telegram message. chatId={}, text={}", chatId, text);
        Map<String, Object> payload = new HashMap<>();
        payload.put("chat_id", chatId);
        payload.put("text", text);
        if (replyMarkup != null) {
            payload.put("reply_markup", replyMarkup);
        }

        try {
            telegramRestClient.post()
                    .uri("/bot{token}/sendMessage", properties.botToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Telegram message sent. chatId={}", chatId);
        } catch (RestClientException e) {
            log.error("Failed to send Telegram message. chatId={}, error={}", chatId, e.getMessage(), e);
            throw e;
        }
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
            throw e;
        }
    }

    private Map<String, Object> buildMainKeyboard() {
        return Map.of(
                "resize_keyboard", true,
                "keyboard", List.of(
                        List.of(Map.of("text", "📅 Сегодня"), Map.of("text", "🗓 Завтра"), Map.of("text", "📆 Неделя")),
                List.of(Map.of("text", "📝 Заметки"), Map.of("text", "✏️ Редактировать заметку"))
                )
        );
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
                return "Укажите ID заметки для редактирования.";
            }
            try {
                UUID noteId = UUID.fromString(intent.noteId());
                Note note = noteRepository.findByIdAndUser(noteId, user).orElse(null);
                if (note == null) {
                    return "Заметка не найдена.";
                }
                note.setContent(intent.noteContent() == null ? note.getContent() : intent.noteContent());
                if (intent.noteContent() != null && !intent.noteContent().isBlank()) {
                    String newTitle = intent.noteContent().length() > 70 ? intent.noteContent().substring(0, 70) : intent.noteContent();
                    note.setTitle(newTitle);
                }
                noteRepository.save(note);
                return "📝 Заметка обновлена: " + note.getId();
            } catch (Exception e) {
                return "Некорректный ID заметки.";
            }
        }

        if (intent.classification() == FilterClassification.MEETING && intent.startsAt() != null && intent.endsAt() != null) {
            CalendarDay day = getOrCreateDay(user, intent.startsAt().toLocalDate());
            Meeting meeting = new Meeting();
            meeting.setCalendarDay(day);
            meeting.setInboundItem(inboundItem);
            meeting.setTitle(intent.title());
            meeting.setStartsAt(intent.startsAt());
            meeting.setEndsAt(intent.endsAt());
            meeting.setExternalLink(intent.externalLink());
            meeting.setStatus(MeetingStatus.CONFIRMED);
            ZoneId zoneId = resolveZone(user.getTimezone());
            String googleLink = googleCalendarService.createEvent(
                    user,
                    intent.title(),
                    intent.startsAt(),
                    intent.endsAt(),
                    intent.externalLink(),
                    zoneId
            );
            if (googleLink != null && !googleLink.isBlank()) {
                meeting.setExternalLink(googleLink);
            }
            meetingRepository.save(meeting);
            day.setBusyLevel(day.getBusyLevel() + 1);
            calendarDayRepository.save(day);
            return withLink(intent.responseText(), meeting.getExternalLink());
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

    private ZoneId resolveZone(String timezone) {
        try {
            return ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone);
        } catch (Exception ignored) {
            return ZoneId.of("UTC");
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
            return "📝 Заметок пока нет.\nСоздайте: `заметка: текст`";
        }
        StringBuilder sb = new StringBuilder("📝 Ваши заметки:\n");
        for (Note note : notes) {
            sb.append("\n• ").append(note.getTitle())
                    .append("\n  ID: ").append(note.getId());
        }
        sb.append("\n\nРедактирование: `редактировать заметку <ID> новый текст`");
        return sb.toString();
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
                || normalized.equals("создай событие")
                || normalized.equals("добавить событие")
                || normalized.equals("добавь событие")
                || normalized.equals("новое событие")
                || normalized.equals("создать встречу")
                || normalized.equals("создай встречу")
                || normalized.startsWith("создать событие ")
                || normalized.startsWith("создай событие ")
                || normalized.startsWith("добавить событие ")
                || normalized.startsWith("добавь событие ")
                || normalized.startsWith("создать встречу ")
                || normalized.startsWith("создай встречу ")
                || EVENT_WIZARD_TRIGGER_PATTERN.matcher(normalized).find();
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

    private WizardResult processEventWizardStep(User user, EventCreationSession session, String text, ZoneId zoneId) {
        if (session.getStep() == null) {
            session.setStep(EventCreationStep.WAIT_DATE);
        }

        String input = text == null ? "" : text.trim();
        if (input.isBlank()) {
            return new WizardResult("Я не вижу ответа. Напишите текстом или нажмите ❌ Отмена.", false);
        }

        if (session.getStep() == EventCreationStep.WAIT_DATE) {
            LocalDate date = parseDate(input, zoneId);
            if (date == null) {
                eventCreationSessionRepository.save(session);
                return new WizardResult("Не распознал дату. Пример: 21.02.2026 или 21 февраля", false);
            }
            session.setMeetingDate(date);
            session.setStep(EventCreationStep.WAIT_TIME);
            eventCreationSessionRepository.save(session);
            return new WizardResult("Шаг 2/4: во сколько? (например: 14:30 или в 14 часов)", false);
        }

        if (session.getStep() == EventCreationStep.WAIT_TIME) {
            LocalTime time = parseTime(input);
            if (time == null) {
                eventCreationSessionRepository.save(session);
                return new WizardResult("Не распознал время. Пример: 14:30 или в 14 часов", false);
            }
            session.setMeetingTime(time);
            session.setStep(EventCreationStep.WAIT_TITLE);
            eventCreationSessionRepository.save(session);
            return new WizardResult("Шаг 3/4: как назвать событие?", false);
        }

        if (session.getStep() == EventCreationStep.WAIT_TITLE) {
            String title = input;
            if (title.length() > 180) {
                title = title.substring(0, 180);
            }
            session.setMeetingTitle(title);
            session.setStep(EventCreationStep.WAIT_DURATION);
            eventCreationSessionRepository.save(session);
            return new WizardResult("Шаг 4/4: длительность? (например: 30 минут, 1 час, 1.5 часа). Можно написать: пропустить", false);
        }

        if (session.getStep() == EventCreationStep.WAIT_DURATION) {
            Integer durationMinutes = parseDurationMinutes(input);
            if (durationMinutes == null) {
                eventCreationSessionRepository.save(session);
                return new WizardResult("Не распознал длительность. Пример: 30 минут, 1 час, 1.5 часа", false);
            }
            session.setDurationMinutes(durationMinutes);
            eventCreationSessionRepository.save(session);

            if (session.getMeetingDate() == null || session.getMeetingTime() == null) {
                session.setStep(EventCreationStep.WAIT_DATE);
                eventCreationSessionRepository.save(session);
                return new WizardResult("Что-то пошло не так — давайте начнем с даты. Пример: 21.02.2026", false);
            }

            OffsetDateTime startsAt = session.getMeetingDate()
                    .atTime(session.getMeetingTime())
                    .atZone(zoneId == null ? ZoneId.of("UTC") : zoneId)
                    .toOffsetDateTime();
            OffsetDateTime endsAt = startsAt.plusMinutes(durationMinutes);

            CalendarDay day = getOrCreateDay(user, session.getMeetingDate());
            Meeting meeting = new Meeting();
            meeting.setCalendarDay(day);
            meeting.setTitle(session.getMeetingTitle() == null || session.getMeetingTitle().isBlank() ? "Событие" : session.getMeetingTitle());
            meeting.setStartsAt(startsAt);
            meeting.setEndsAt(endsAt);
            meeting.setStatus(MeetingStatus.CONFIRMED);
            meetingRepository.save(meeting);

            day.setBusyLevel(day.getBusyLevel() + 1);
            calendarDayRepository.save(day);

            eventCreationSessionRepository.delete(session);
            return new WizardResult("✅ Событие создано: " + meeting.getTitle() + "\n🕒 " + startsAt.toLocalDate() + " " + startsAt.toLocalTime().withSecond(0).withNano(0), true);
        }

        session.setStep(EventCreationStep.WAIT_DATE);
        eventCreationSessionRepository.save(session);
        return new WizardResult("Давайте начнем заново. Шаг 1/4: на какую дату?", false);
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
            int resolvedYear = year != null ? year : LocalDate.now(zoneId == null ? ZoneId.of("UTC") : zoneId).getYear();
            try {
                LocalDate candidate = LocalDate.of(resolvedYear, month, day);
                if (year == null) {
                    LocalDate today = LocalDate.now(zoneId == null ? ZoneId.of("UTC") : zoneId);
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
            int resolvedYear = year != null ? year : LocalDate.now(zoneId == null ? ZoneId.of("UTC") : zoneId).getYear();
            try {
                LocalDate candidate = LocalDate.of(resolvedYear, month, day);
                if (year == null) {
                    LocalDate today = LocalDate.now(zoneId == null ? ZoneId.of("UTC") : zoneId);
                    if (candidate.isBefore(today.minusDays(1))) {
                        candidate = candidate.plusYears(1);
                    }
                }
                return candidate;
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
        Matcher m1 = TIME_COLON_PATTERN.matcher(normalized);
        if (m1.find()) {
            int hour = Integer.parseInt(m1.group(1));
            int minute = Integer.parseInt(m1.group(2));
            try {
                return LocalTime.of(hour, minute);
            } catch (Exception ignored) {
                return null;
            }
        }
        Matcher m2 = TIME_HOUR_ONLY_PATTERN.matcher(normalized);
        if (m2.find()) {
            int hour = Integer.parseInt(m2.group(1));
            try {
                return LocalTime.of(hour, 0);
            } catch (Exception ignored) {
                return null;
            }
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

        String loginUrl = googleOAuthService.createConnectUrl(chatId).orElse(null);
        if (loginUrl != null && !loginUrl.isBlank()) {
            sendInlineGoogleConnectButton(chatId, loginUrl);
        } else {
            sendMessage(chatId,
                    "Для входа через Google нужен публичный URL приложения. " +
                            "Если тест локально на этом же ноутбуке: APP_PUBLIC_BASE_URL=http://localhost:8010",
                    false);
        }
    }

    private void sendInlineGoogleConnectButton(Long chatId, String loginUrl) {
        Map<String, Object> inlineMarkup = Map.of(
                "inline_keyboard", List.of(
                        List.of(Map.of("text", "Войти в Google", "url", loginUrl))
                )
        );
        Map<String, Object> payload = new HashMap<>();
        payload.put("chat_id", chatId);
        payload.put("text", "Нажмите, чтобы войти в Google и синхронизировать календарь:");
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
        if (message.contains("429") || message.contains("quota")) {
            return "Не удалось распознать голос: закончилась квота Gemini STT.";
        }
        return "Не удалось распознать голос. Проверьте квоту Gemini или локальный Whisper и попробуйте еще раз.";
    }
}

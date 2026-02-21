package com.aichef.service;

import com.aichef.domain.enums.FilterClassification;
import com.aichef.domain.enums.InboundStatus;
import com.aichef.domain.enums.PriorityLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class MessageUnderstandingService {

    private static final Pattern LINK_PATTERN = Pattern.compile("(https?://\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_COLON_PATTERN = Pattern.compile("\\b(?:в|на)?\\s*(\\d{1,2})[:.](\\d{2})\\b");
    private static final Pattern TIME_HOURS_PATTERN = Pattern.compile("\\b(?:в|на)?\\s*(\\d{1,2})\\s*(?:час|часа|часов)\\b");
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?");
    private static final Pattern DATE_TEXT_PATTERN = Pattern.compile(
            "\\b(\\d{1,2})\\s+(январ[яе]|феврал[яе]|март[а]?|апрел[яе]|ма[йя]|июн[яе]|июл[яе]|август[а]?|сентябр[яе]|октябр[яе]|ноябр[яе]|декабр[яе])(?:\\s+(\\d{4}))?\\b");
    private static final Pattern DATE_WORDS_PATTERN = Pattern.compile(
            "(?iu)(?<!\\p{L})([а-яё\\-]+(?:\\s+[а-яё\\-]+)?)\\s+(январ[яе]|феврал[яе]|март[а]?|апрел[яе]|ма[йя]|июн[яе]|июл[яе]|август[а]?|сентябр[яе]|октябр[яе]|ноябр[яе]|декабр[яе])(?:\\s+([а-яё\\s\\-]+?)\\s+г(?:ода|од)?)?(?!\\p{L})");
    private static final Pattern HOUR_WORDS_PATTERN = Pattern.compile("(?iu)(?<!\\p{L})(?:в\\s+)?([а-яё\\-]+(?:\\s+[а-яё\\-]+)?)\\s+час(?:а|ов)?(?!\\p{L})");
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
    private static final Map<String, Integer> RU_DAY_WORDS = buildDayWords();
    private static final Map<String, Integer> RU_NUMBER_WORDS = buildNumberWords();
    private final OllamaStructuredParsingService ollamaStructuredParsingService;

    public MessageIntent decide(String sourceText, ZoneId zoneId) {
        if (sourceText == null || sourceText.isBlank()) {
            return clarificationIntent();
        }

        String text = sourceText.trim();
        String normalized = text.toLowerCase(Locale.ROOT);

        MessageIntent noteEdit = parseNoteEdit(text, normalized);
        if (noteEdit != null) {
            return noteEdit;
        }

        MessageIntent noteDelete = parseNoteDelete(text, normalized);
        if (noteDelete != null) {
            return noteDelete;
        }

        MessageIntent noteCreate = parseNoteCreate(text, normalized);
        if (noteCreate != null) {
            return noteCreate;
        }

        if (isShowNotesRequest(normalized)) {
            return new MessageIntent(
                    BotAction.SHOW_NOTES,
                    FilterClassification.INFO_ONLY,
                    InboundStatus.PROCESSED,
                    "Мои заметки",
                    PriorityLevel.LOW,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "Показываю ваши заметки."
            );
        }

        if (isGoogleConnectRequest(normalized)) {
            return new MessageIntent(
                    BotAction.INFO,
                    FilterClassification.INFO_ONLY,
                    InboundStatus.PROCESSED,
                    "Google connect",
                    PriorityLevel.LOW,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "Чтобы синхронизировать Google Calendar, нажмите кнопку подключения."
            );
        }

        if (isScheduleRequest(normalized)) {
            return scheduleIntent(normalized);
        }

        MessageIntent uiActionIntent = parseUiActionIntent(normalized);
        if (uiActionIntent != null) {
            return uiActionIntent;
        }

        if (isNoise(normalized)) {
            return new MessageIntent(
                    BotAction.IGNORE,
                    FilterClassification.IGNORE,
                    InboundStatus.IGNORED,
                    "Игнор",
                    PriorityLevel.LOW,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "Принял."
            );
        }

        boolean hasMeetingHint = hasAny(normalized,
                "встреч", "созвон", "митинг", "call", "meeting", "zoom", "google meet", "видео", "переговор");
        boolean hasTaskHint = hasAny(normalized,
                "задач", "сделать", "надо", "нужно", "подготов", "отправ", "купить", "написать", "позвонить");

        String link = findLink(text);
        if (link != null && !hasTaskHint) {
            hasMeetingHint = true;
        }

        OllamaStructuredParsingService.ParsedEventData llmParsed = ollamaStructuredParsingService.extractEventData(text, zoneId);
        if (llmParsed.isCreateMeetingIntent()) {
            LocalDate parsedDate = llmParsed.date();
            LocalTime parsedTime = llmParsed.time();
            OffsetDateTime start = (parsedDate != null && parsedTime != null)
                    ? OffsetDateTime.now(zoneId)
                    .withYear(parsedDate.getYear())
                    .withMonth(parsedDate.getMonthValue())
                    .withDayOfMonth(parsedDate.getDayOfMonth())
                    .withHour(parsedTime.getHour())
                    .withMinute(parsedTime.getMinute())
                    .withSecond(0)
                    .withNano(0)
                    : inferMeetingStart(normalized, zoneId);
            int durationMinutes = llmParsed.durationMinutes() != null && llmParsed.durationMinutes() > 0
                    ? llmParsed.durationMinutes()
                    : 60;
            OffsetDateTime end = start.plusMinutes(durationMinutes);
            String title = llmParsed.title() == null || llmParsed.title().isBlank()
                    ? cleanupMeetingTitle(text)
                    : cleanupTitle(stripCreateCommandPhrases(llmParsed.title()), "Встреча");
            return new MessageIntent(
                    BotAction.CREATE_MEETING,
                    FilterClassification.MEETING,
                    InboundStatus.PROCESSED,
                    title,
                    PriorityLevel.HIGH,
                    start,
                    end,
                    null,
                    null,
                    null,
                    null,
                    link,
                    "✅ Встреча добавлена: " + title + "\n🕒 " + start.toLocalDate() + " " + start.toLocalTime().withSecond(0).withNano(0)
            );
        }

        if (hasMeetingHint) {
            OffsetDateTime start = inferMeetingStart(normalized, zoneId);
            OffsetDateTime end = start.plusHours(1);
            String title = cleanupMeetingTitle(text);
            return new MessageIntent(
                    BotAction.CREATE_MEETING,
                    FilterClassification.MEETING,
                    InboundStatus.PROCESSED,
                    title,
                    PriorityLevel.HIGH,
                    start,
                    end,
                    null,
                    null,
                    null,
                    null,
                    link,
                    "✅ Встреча добавлена: " + title + "\n🕒 " + start.toLocalDate() + " " + start.toLocalTime().withSecond(0).withNano(0)
            );
        }

        if (hasTaskHint) {
            OffsetDateTime dueAt = inferTaskDue(normalized, zoneId);
            String title = cleanupTitle(text, "Задача");
            return new MessageIntent(
                    BotAction.CREATE_TASK,
                    FilterClassification.TASK,
                    InboundStatus.PROCESSED,
                    title,
                    PriorityLevel.MEDIUM,
                    null,
                    null,
                    dueAt,
                    null,
                    null,
                    null,
                    link,
                    "✅ Задача добавлена: " + title
            );
        }

        String noteTitle = cleanupTitle(text, "Заметка");
        return new MessageIntent(
                BotAction.CREATE_NOTE,
                FilterClassification.INFO_ONLY,
                InboundStatus.PROCESSED,
                noteTitle,
                PriorityLevel.LOW,
                null,
                null,
                null,
                null,
                null,
                text,
                link,
                "📝 Сохранил как заметку."
        );
    }

    private MessageIntent parseUiActionIntent(String normalized) {
        if (hasAny(normalized, "✏️ редактировать заметку", "редактировать заметку")) {
            return new MessageIntent(
                    BotAction.INFO,
                    FilterClassification.INFO_ONLY,
                    InboundStatus.PROCESSED,
                    "Редактирование заметки",
                    PriorityLevel.LOW,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "Введите: `✏️ <номер> новый текст`"
            );
        }
        if (hasAny(normalized, "🗑 удалить заметку", "удалить заметку")) {
            return new MessageIntent(
                    BotAction.INFO,
                    FilterClassification.INFO_ONLY,
                    InboundStatus.PROCESSED,
                    "Удаление заметки",
                    PriorityLevel.LOW,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "Введите: `🗑 <номер>`"
            );
        }
        return null;
    }

    private MessageIntent scheduleIntent(String normalized) {
        ScheduleRange range = ScheduleRange.TODAY;
        if (hasAny(normalized, "завтра", "tomorrow", "🗓 завтра")) {
            range = ScheduleRange.TOMORROW;
        } else if (hasAny(normalized, "неделя", "week", "неделю", "📆 неделя")) {
            range = ScheduleRange.WEEK;
        }

        return new MessageIntent(
                BotAction.SHOW_SCHEDULE,
                FilterClassification.INFO_ONLY,
                InboundStatus.PROCESSED,
                "Расписание",
                PriorityLevel.LOW,
                null,
                null,
                null,
                range,
                null,
                null,
                null,
                "Показываю расписание."
        );
    }

    private MessageIntent clarificationIntent() {
        return new MessageIntent(
                BotAction.ASK_CLARIFICATION,
                FilterClassification.ASK_CLARIFICATION,
                InboundStatus.NEEDS_CLARIFICATION,
                "Уточнить запрос",
                PriorityLevel.MEDIUM,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "Не вижу текста запроса. Отправьте, пожалуйста, задачу или встречу текстом."
        );
    }

    private boolean isScheduleRequest(String normalized) {
        return hasAny(normalized,
                "📅 сегодня", "📆 неделя", "🗓 завтра", "сегодня", "завтра", "неделя", "расписание", "календар", "what\'s today", "schedule");
    }

    private boolean isGoogleConnectRequest(String normalized) {
        if (hasAny(normalized, "🔗 подключить google", "подключить google", "google connect")) {
            return true;
        }
        boolean hasGoogleWord = hasAny(normalized, "google", "гугл");
        boolean hasConnectIntent = hasAny(normalized, "подключ", "синхрониз", "oauth", "авториза", "календар");
        return hasGoogleWord && hasConnectIntent;
    }

    private boolean isShowNotesRequest(String normalized) {
        return hasAny(normalized, "мои заметки", "заметки", "/notes", "📝 заметки");
    }

    private MessageIntent parseNoteCreate(String text, String normalized) {
        String marker = null;
        if (normalized.startsWith("заметка:")) {
            marker = "заметка:";
        } else if (normalized.startsWith("note:")) {
            marker = "note:";
        }
        if (marker == null) {
            return null;
        }
        String content = text.substring(marker.length()).trim();
        if (content.isBlank()) {
            return clarificationIntent();
        }
        String title = content.length() > 70 ? content.substring(0, 70) : content;
        return new MessageIntent(
                BotAction.CREATE_NOTE,
                FilterClassification.INFO_ONLY,
                InboundStatus.PROCESSED,
                title,
                PriorityLevel.LOW,
                null,
                null,
                null,
                null,
                null,
                content,
                null,
                "📝 Заметка сохранена."
        );
    }

    private MessageIntent parseNoteEdit(String text, String normalized) {
        if (normalized.startsWith("✏️")) {
            String payload = text.replaceFirst("^\\s*✏️\\s*", "").trim();
            String[] tokens = payload.split("\\s+", 2);
            if (tokens.length < 2) {
                return clarificationIntent();
            }
            return new MessageIntent(
                    BotAction.EDIT_NOTE,
                    FilterClassification.INFO_ONLY,
                    InboundStatus.PROCESSED,
                    "Редактирование заметки",
                    PriorityLevel.LOW,
                    null,
                    null,
                    null,
                    null,
                    tokens[0].trim(),
                    tokens[1].trim(),
                    null,
                    "📝 Заметка обновлена."
            );
        }

        if (!normalized.startsWith("редактировать заметку") && !normalized.startsWith("/edit_note")) {
            return null;
        }
        String[] tokens = text.split("\\s+", 4);
        if (tokens.length < 3) {
            return clarificationIntent();
        }
        String noteId = tokens[2].trim();
        String content = tokens.length >= 4 ? tokens[3].trim() : "";
        if (content.isBlank()) {
            return clarificationIntent();
        }
        return new MessageIntent(
                BotAction.EDIT_NOTE,
                FilterClassification.INFO_ONLY,
                InboundStatus.PROCESSED,
                "Редактирование заметки",
                PriorityLevel.LOW,
                null,
                null,
                null,
                null,
                noteId,
                content,
                null,
                "📝 Заметка обновлена."
        );
    }

    private MessageIntent parseNoteDelete(String text, String normalized) {
        if (normalized.startsWith("🗑")) {
            String payload = text.replaceFirst("^\\s*🗑\\s*", "").trim();
            if (payload.isBlank()) {
                return clarificationIntent();
            }
            String noteId = payload.split("\\s+")[0];
            return new MessageIntent(
                    BotAction.DELETE_NOTE,
                    FilterClassification.INFO_ONLY,
                    InboundStatus.PROCESSED,
                    "Удаление заметки",
                    PriorityLevel.LOW,
                    null,
                    null,
                    null,
                    null,
                    noteId,
                    null,
                    null,
                    "🗑 Заметка удалена."
            );
        }

        if (!normalized.startsWith("удалить заметку") && !normalized.startsWith("/delete_note")) {
            return null;
        }
        String[] tokens = text.split("\\s+", 4);
        if (tokens.length < 3) {
            return clarificationIntent();
        }
        String noteId = tokens[2].trim();
        if (noteId.isBlank()) {
            return clarificationIntent();
        }
        return new MessageIntent(
                BotAction.DELETE_NOTE,
                FilterClassification.INFO_ONLY,
                InboundStatus.PROCESSED,
                "Удаление заметки",
                PriorityLevel.LOW,
                null,
                null,
                null,
                null,
                noteId,
                null,
                null,
                "🗑 Заметка удалена."
        );
    }

    private boolean isNoise(String normalized) {
        return normalized.length() <= 2 || hasAny(normalized, "ок", "окей", "спс", "thanks", "понял");
    }

    private boolean hasAny(String normalized, String... words) {
        for (String word : words) {
            if (normalized.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private String cleanupTitle(String text, String fallback) {
        String title = text.replaceAll("\\s+", " ").trim();
        return title.isEmpty() ? fallback : (title.length() > 180 ? title.substring(0, 180) : title);
    }

    private String cleanupMeetingTitle(String text) {
        String title = text;
        title = title.replaceAll("(?iu)^\\s*(ну\\s+)?(хорошо\\s*,?\\s*)?", "");
        title = title.replaceAll("(?iu)^\\s*(создай|создать|сделай|сделать|добавь|добавить|поставь|запланируй|перенеси|измени)\\s+(мне\\s+)?(событи[еяю]|встреч[ауеи]|митинг)\\s*", "");
        title = title.replaceAll("(?iu)^\\s*(на\\s+)?\\d{1,2}[./]\\d{1,2}(?:[./]\\d{2,4})?\\s*", "");
        title = title.replaceAll("(?iu)\\b(сегодня|завтра|послезавтра)\\b", " ");
        title = title.replaceAll("(?iu)\\bна\\s+\\d{1,2}[:.]\\d{2}\\b", " ");
        title = title.replaceAll("(?iu)\\b\\d{1,2}\\s*(?:час|часа|часов)\\b", " ");
        title = title.replaceAll("(?iu)\\b\\d{1,2}[./]\\d{1,2}(?:[./]\\d{2,4})?\\b", " ");
        title = title.replaceAll("(?iu)\\bв\\s+\\d{1,2}[:.]\\d{2}\\b", " ");
        title = title.replaceAll("(?iu)\\b\\d{1,2}\\s+(январ[яе]|феврал[яе]|март[а]?|апрел[яе]|ма[йя]|июн[яе]|июл[яе]|август[а]?|сентябр[яе]|октябр[яе]|ноябр[яе]|декабр[яе])(?:\\s+\\d{4})?\\b", " ");
        title = title.replaceAll("(?iu)\\b[а-яё\\-]+(?:\\s+[а-яё\\-]+)?\\s+(январ[яе]|феврал[яе]|март[а]?|апрел[яе]|ма[йя]|июн[яе]|июл[яе]|август[а]?|сентябр[яе]|октябр[яе]|ноябр[яе]|декабр[яе])(?:\\s+[а-яё\\s\\-]+\\s+г(?:ода|од)?)?\\b", " ");
        title = title.replaceAll("(?iu)\\b[а-яё\\-]+(?:\\s+[а-яё\\-]+)?\\s+час(?:а|ов)?\\b", " ");
        title = title.replaceAll("(?iu)\\bв\\s+[а-яё\\-]+(?:\\s+[а-яё\\-]+)?\\s+(утра|дня|вечера|ночи)\\b", " ");
        title = title.replaceAll("(?iu)\\bдлительност\\p{L}*\\s+[а-яё0-9\\s.,\\-]+$", " ");
        title = title.replaceAll("(?iu)\\bдлительност\\p{L}*\\b", " ");
        title = title.replaceAll("(?iu)\\b(год|года)\\b", " ");
        title = title.replaceAll("(?iu)\\bпоставь\\b|\\bсоздай\\b|\\bсделай\\b|\\bдобавь\\b", " ");
        title = stripCreateCommandPhrases(title);
        title = cutAtTemporalTail(title);
        return cleanupTitle(title, "Встреча");
    }

    private String stripCreateCommandPhrases(String source) {
        if (source == null) {
            return "";
        }
        String cleaned = source;
        cleaned = cleaned.replaceAll("(?iu)\\b(созда(й|ть)|добав(ь|ить)|запланиру(й|йте|ю)|сдела(й|ть))\\s+(мне\\s+)?(событи\\p{L}*|встреч\\p{L}*)\\b", " ");
        cleaned = cleaned.replaceAll("(?iu)\\b(созда(й|ть)|добав(ь|ить)|запланиру(й|йте|ю)|сдела(й|ть))\\b", " ");
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

    private String findLink(String text) {
        Matcher matcher = LINK_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private OffsetDateTime inferMeetingStart(String normalized, ZoneId zoneId) {
        LocalDate date = inferDate(normalized, zoneId);
        LocalTime time = inferTime(normalized);
        return OffsetDateTime.now(zoneId)
                .withYear(date.getYear())
                .withMonth(date.getMonthValue())
                .withDayOfMonth(date.getDayOfMonth())
                .withHour(time.getHour())
                .withMinute(time.getMinute())
                .withSecond(0)
                .withNano(0);
    }

    private OffsetDateTime inferTaskDue(String normalized, ZoneId zoneId) {
        LocalDate date = inferDate(normalized, zoneId);
        LocalTime time = hasAny(normalized, "сегодня", "today") ? LocalTime.of(20, 0) : LocalTime.of(12, 0);
        return OffsetDateTime.now(zoneId)
                .withYear(date.getYear())
                .withMonth(date.getMonthValue())
                .withDayOfMonth(date.getDayOfMonth())
                .withHour(time.getHour())
                .withMinute(time.getMinute())
                .withSecond(0)
                .withNano(0);
    }

    private LocalDate inferDate(String normalized, ZoneId zoneId) {
        LocalDate now = LocalDate.now(zoneId);
        if (hasAny(normalized, "сегодня", "today")) {
            return now;
        }
        if (hasAny(normalized, "послезавтра")) {
            return now.plusDays(2);
        }
        if (hasAny(normalized, "завтра", "tomorrow")) {
            return now.plusDays(1);
        }

        Matcher dateMatcher = DATE_PATTERN.matcher(normalized);
        if (dateMatcher.find()) {
            int day = Integer.parseInt(dateMatcher.group(1));
            int month = Integer.parseInt(dateMatcher.group(2));
            String yearStr = dateMatcher.group(3);
            int year = yearStr == null ? now.getYear() : Integer.parseInt(yearStr.length() == 2 ? "20" + yearStr : yearStr);
            try {
                return LocalDate.of(year, month, day);
            } catch (Exception ignored) {
                return now;
            }
        }

        Matcher textDateMatcher = DATE_TEXT_PATTERN.matcher(normalized);
        if (textDateMatcher.find()) {
            int day = Integer.parseInt(textDateMatcher.group(1));
            String monthRaw = textDateMatcher.group(2);
            Integer month = resolveMonth(monthRaw);
            if (month != null) {
                String yearRaw = textDateMatcher.group(3);
                int year = (yearRaw == null || yearRaw.isBlank()) ? now.getYear() : Integer.parseInt(yearRaw);
                try {
                    return LocalDate.of(year, month, day);
                } catch (Exception ignored) {
                    return now;
                }
            }
        }

        Matcher wordsDateMatcher = DATE_WORDS_PATTERN.matcher(normalized);
        while (wordsDateMatcher.find()) {
            Integer day = RU_DAY_WORDS.get(wordsDateMatcher.group(1).trim());
            Integer month = resolveMonth(wordsDateMatcher.group(2));
            if (day == null || month == null) {
                continue;
            }
            int year = now.getYear();
            String yearWords = wordsDateMatcher.group(3);
            Integer parsedYear = parseRussianWordsNumber(yearWords);
            if (parsedYear != null && parsedYear >= 1900 && parsedYear <= 2200) {
                year = parsedYear;
            }
            try {
                return LocalDate.of(year, month, day);
            } catch (Exception ignored) {
                // keep scanning next possible date phrase in the same text
            }
        }

        return now;
    }

    private LocalTime inferTime(String normalized) {
        String withoutDates = normalized.replaceAll("\\b\\d{1,2}[.]\\d{1,2}(?:[.]\\d{2,4})?\\b", " ");
        Matcher timeMatcher = TIME_COLON_PATTERN.matcher(withoutDates);
        while (timeMatcher.find()) {
            int hour = Integer.parseInt(timeMatcher.group(1));
            int minute = Integer.parseInt(timeMatcher.group(2));
            if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
                return LocalTime.of(hour, minute);
            }
        }

        Matcher hourMatcher = TIME_HOURS_PATTERN.matcher(withoutDates);
        while (hourMatcher.find()) {
            int hour = Integer.parseInt(hourMatcher.group(1));
            if (hour >= 0 && hour <= 23) {
                return LocalTime.of(hour, 0);
            }
        }

        Matcher hourWordsMatcher = HOUR_WORDS_PATTERN.matcher(withoutDates);
        while (hourWordsMatcher.find()) {
            Integer hour = parseRussianWordsNumber(hourWordsMatcher.group(1));
            if (hour != null && hour >= 0 && hour <= 23) {
                return LocalTime.of(hour, 0);
            }
        }

        if (hasAny(normalized, "утром")) {
            return LocalTime.of(10, 0);
        }
        if (hasAny(normalized, "днем", "днём")) {
            return LocalTime.of(14, 0);
        }
        if (hasAny(normalized, "вечером")) {
            return LocalTime.of(18, 0);
        }

        return LocalTime.of(11, 0);
    }

    private Integer resolveMonth(String monthRaw) {
        if (monthRaw == null || monthRaw.isBlank()) {
            return null;
        }
        String normalized = monthRaw.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Integer> month : RUS_MONTHS.entrySet()) {
            if (normalized.startsWith(month.getKey())) {
                return month.getValue();
            }
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

    private static Map<String, Integer> buildDayWords() {
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

    private static Map<String, Integer> buildNumberWords() {
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
}

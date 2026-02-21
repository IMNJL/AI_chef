package com.aichef.service;

import com.aichef.domain.enums.MeetingStatus;
import com.aichef.domain.model.Meeting;
import com.aichef.domain.model.TaskItem;
import com.aichef.domain.model.User;
import com.aichef.domain.model.UserGoogleConnection;
import com.aichef.repository.MeetingRepository;
import com.aichef.repository.TaskItemRepository;
import com.aichef.repository.UserGoogleConnectionRepository;
import com.aichef.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IcsFeedService {

    private static final DateTimeFormatter ICS_DT_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private final UserGoogleConnectionRepository connectionRepository;
    private final UserRepository userRepository;
    private final MeetingRepository meetingRepository;
    private final TaskItemRepository taskItemRepository;
    private final GoogleCalendarService googleCalendarService;

    @Transactional(readOnly = true)
    public Optional<String> buildIcsByToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        UserGoogleConnection connection = connectionRepository.findByIcsToken(token).orElse(null);
        if (connection == null || connection.getUserId() == null) {
            return Optional.empty();
        }
        User user = userRepository.findById(connection.getUserId()).orElse(null);
        if (user == null) {
            return Optional.empty();
        }
        return Optional.of(buildIcs(user));
    }

    public String buildIcs(User user) {
        List<Meeting> meetings = meetingRepository.findByCalendarDay_UserOrderByStartsAtAsc(user);
        ZoneId zoneId = resolveZone(user);
        LocalDate from = LocalDate.now(zoneId).minusYears(1);
        LocalDate to = LocalDate.now(zoneId).plusYears(2);
        List<TaskItem> tasks = taskItemRepository.findByCalendarDay_UserAndCalendarDay_DayDateBetweenOrderByDueAtAsc(user, from, to);
        List<CalendarEventView> googleEvents = googleCalendarService.listEvents(user, from, to, zoneId);

        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n");
        sb.append("VERSION:2.0\r\n");
        sb.append("PRODID:-//AI Chef//Assistant Calendar//EN\r\n");
        sb.append("CALSCALE:GREGORIAN\r\n");
        sb.append("METHOD:PUBLISH\r\n");
        sb.append("X-WR-CALNAME:assistant\r\n");
        Set<String> eventKeys = new HashSet<>();

        for (Meeting meeting : meetings) {
            if (meeting.getStatus() == MeetingStatus.CANCELED) {
                continue;
            }
            String eventKey = buildEventKey(meeting.getTitle(), meeting.getStartsAt().toString(), meeting.getEndsAt().toString());
            eventKeys.add(eventKey);
            String uid = meeting.getId() + "@aichef";
            String dtStamp = meeting.getUpdatedAt() == null
                    ? meeting.getStartsAt().atZoneSameInstant(ZoneOffset.UTC).format(ICS_DT_FMT)
                    : meeting.getUpdatedAt().atZoneSameInstant(ZoneOffset.UTC).format(ICS_DT_FMT);
            String dtStart = meeting.getStartsAt().atZoneSameInstant(ZoneOffset.UTC).format(ICS_DT_FMT);
            String dtEnd = meeting.getEndsAt().atZoneSameInstant(ZoneOffset.UTC).format(ICS_DT_FMT);

            sb.append("BEGIN:VEVENT\r\n");
            sb.append("UID:").append(uid).append("\r\n");
            sb.append("DTSTAMP:").append(dtStamp).append("\r\n");
            sb.append("DTSTART:").append(dtStart).append("\r\n");
            sb.append("DTEND:").append(dtEnd).append("\r\n");
            sb.append("SUMMARY:").append(escapeIcs(meeting.getTitle())).append("\r\n");
            if (meeting.getExternalLink() != null && !meeting.getExternalLink().isBlank()) {
                sb.append("URL:").append(escapeIcs(meeting.getExternalLink())).append("\r\n");
            }
            sb.append("END:VEVENT\r\n");
        }

        for (CalendarEventView event : googleEvents) {
            String eventKey = buildEventKey(event.title(), event.startsAt().toString(), event.endsAt().toString());
            if (eventKeys.contains(eventKey)) {
                continue;
            }
            String uid = UUID.nameUUIDFromBytes((eventKey + "|google").getBytes()).toString() + "@aichef";
            String dtStamp = event.startsAt().atZoneSameInstant(ZoneOffset.UTC).format(ICS_DT_FMT);
            String dtStart = event.startsAt().atZoneSameInstant(ZoneOffset.UTC).format(ICS_DT_FMT);
            String dtEnd = event.endsAt().atZoneSameInstant(ZoneOffset.UTC).format(ICS_DT_FMT);

            sb.append("BEGIN:VEVENT\r\n");
            sb.append("UID:").append(uid).append("\r\n");
            sb.append("DTSTAMP:").append(dtStamp).append("\r\n");
            sb.append("DTSTART:").append(dtStart).append("\r\n");
            sb.append("DTEND:").append(dtEnd).append("\r\n");
            sb.append("SUMMARY:").append(escapeIcs(event.title())).append("\r\n");
            if (event.link() != null && !event.link().isBlank()) {
                sb.append("URL:").append(escapeIcs(event.link())).append("\r\n");
            }
            sb.append("END:VEVENT\r\n");
        }

        for (TaskItem task : tasks) {
            if (task.isCompleted()) {
                continue;
            }
            OffsetDateTime dueAt = task.getDueAt();
            OffsetDateTime startsAt;
            OffsetDateTime endsAt;
            if (dueAt != null) {
                startsAt = dueAt.withSecond(0).withNano(0);
                endsAt = startsAt.plusMinutes(30);
            } else {
                startsAt = task.getCalendarDay().getDayDate()
                        .atTime(12, 0)
                        .atZone(zoneId)
                        .toOffsetDateTime();
                endsAt = startsAt.plusMinutes(30);
            }
            String eventKey = buildEventKey("task:" + task.getTitle(), startsAt.toString(), endsAt.toString());
            if (eventKeys.contains(eventKey)) {
                continue;
            }
            eventKeys.add(eventKey);
            String uid = task.getId() + "@aichef-task";
            String dtStamp = startsAt.atZoneSameInstant(ZoneOffset.UTC).format(ICS_DT_FMT);
            String dtStart = startsAt.atZoneSameInstant(ZoneOffset.UTC).format(ICS_DT_FMT);
            String dtEnd = endsAt.atZoneSameInstant(ZoneOffset.UTC).format(ICS_DT_FMT);

            sb.append("BEGIN:VEVENT\r\n");
            sb.append("UID:").append(uid).append("\r\n");
            sb.append("DTSTAMP:").append(dtStamp).append("\r\n");
            sb.append("DTSTART:").append(dtStart).append("\r\n");
            sb.append("DTEND:").append(dtEnd).append("\r\n");
            sb.append("SUMMARY:").append(escapeIcs("Задача: " + task.getTitle())).append("\r\n");
            sb.append("END:VEVENT\r\n");
        }

        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    private ZoneId resolveZone(User user) {
        if (user == null || user.getTimezone() == null || user.getTimezone().isBlank()) {
            return ZoneId.of("Europe/Moscow");
        }
        try {
            return ZoneId.of(user.getTimezone());
        } catch (Exception ignored) {
            return ZoneId.of("Europe/Moscow");
        }
    }

    private String buildEventKey(String title, String startsAt, String endsAt) {
        return (title == null ? "" : title.trim()) + "|" + startsAt + "|" + endsAt;
    }

    private String escapeIcs(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n");
    }
}

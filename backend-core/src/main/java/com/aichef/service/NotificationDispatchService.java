package com.aichef.service;

import com.aichef.domain.enums.RelatedType;
import com.aichef.domain.model.Meeting;
import com.aichef.domain.model.Notification;
import com.aichef.domain.model.TaskItem;
import com.aichef.domain.model.User;
import com.aichef.repository.MeetingRepository;
import com.aichef.repository.NotificationRepository;
import com.aichef.repository.TaskItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

    private static final DateTimeFormatter REMINDER_TIME_FMT = DateTimeFormatter.ofPattern("dd.MM HH:mm", new Locale("ru"));

    private final NotificationRepository notificationRepository;
    private final MeetingRepository meetingRepository;
    private final TaskItemRepository taskItemRepository;
    private final TelegramBotService telegramBotService;

    @Scheduled(fixedDelayString = "${app.notifications.dispatch-interval-ms:15000}")
    @Transactional
    public void dispatchDueNotifications() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Notification> dueNotifications =
                notificationRepository.findTop100BySentFalseAndNotifyAtLessThanEqualOrderByNotifyAtAsc(now);
        if (dueNotifications.isEmpty()) {
            return;
        }

        for (Notification notification : dueNotifications) {
            try {
                String text = buildMessage(notification);
                if (text == null || text.isBlank()) {
                    markSent(notification);
                    continue;
                }
                Long chatId = notification.getUser() == null ? null : notification.getUser().getTelegramId();
                if (chatId == null) {
                    markSent(notification);
                    continue;
                }
                telegramBotService.sendMessage(chatId, text);
                markSent(notification);
            } catch (Exception e) {
                log.error("Failed to dispatch notification {}: {}", notification.getId(), e.getMessage(), e);
            }
        }
    }

    private String buildMessage(Notification notification) {
        if (notification.getRelatedType() == RelatedType.MEETING) {
            Meeting meeting = meetingRepository.findById(notification.getRelatedId()).orElse(null);
            if (meeting == null || meeting.getStatus() == com.aichef.domain.enums.MeetingStatus.CANCELED) {
                return null;
            }
            ZoneId zoneId = resolveZone(notification.getUser());
            String time = meeting.getStartsAt().atZoneSameInstant(zoneId).format(REMINDER_TIME_FMT);
            long minutesBefore = Duration.between(notification.getNotifyAt(), meeting.getStartsAt()).toMinutes();
            if (minutesBefore <= 1) {
                return "⏰ Напоминание: событие \"" + meeting.getTitle() + "\" начинается сейчас.\n🕒 " + time;
            }
            return "⏰ Напоминание: событие \"" + meeting.getTitle() + "\" начнется через "
                    + formatLeadTime(minutesBefore) + ".\n🕒 " + time;
        }

        if (notification.getRelatedType() == RelatedType.TASK) {
            TaskItem task = taskItemRepository.findById(notification.getRelatedId()).orElse(null);
            if (task == null) {
                return null;
            }
            return "⏰ Напоминание: задача \"" + task.getTitle() + "\"";
        }

        return null;
    }

    private String formatLeadTime(long minutesBefore) {
        if (minutesBefore < 60) {
            return minutesBefore + " мин";
        }
        long hours = minutesBefore / 60;
        long mins = minutesBefore % 60;
        if (mins == 0) {
            return hours + " ч";
        }
        return hours + " ч " + mins + " мин";
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

    private void markSent(Notification notification) {
        notification.setSent(true);
        notification.setSentAt(OffsetDateTime.now());
        notificationRepository.save(notification);
    }
}

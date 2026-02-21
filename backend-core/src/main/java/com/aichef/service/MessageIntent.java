package com.aichef.service;

import com.aichef.domain.enums.FilterClassification;
import com.aichef.domain.enums.InboundStatus;
import com.aichef.domain.enums.PriorityLevel;

import java.time.OffsetDateTime;

public record MessageIntent(
        BotAction action,
        FilterClassification classification,
        InboundStatus status,
        String title,
        PriorityLevel priority,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        OffsetDateTime dueAt,
        ScheduleRange scheduleRange,
        String noteId,
        String noteContent,
        String externalLink,
        String responseText
) {
}

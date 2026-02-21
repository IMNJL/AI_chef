package com.aichef.service;

import java.time.OffsetDateTime;

public record CalendarEventView(
        String title,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        String source,
        String link
) {
}

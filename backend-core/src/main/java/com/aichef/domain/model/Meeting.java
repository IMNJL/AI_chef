package com.aichef.domain.model;

import com.aichef.domain.enums.MeetingStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "meetings")
public class Meeting extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "calendar_day_id", nullable = false)
    private CalendarDay calendarDay;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inbound_item_id")
    private InboundItem inboundItem;

    @Column(nullable = false)
    private String title;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private OffsetDateTime endsAt;

    private String location;

    @Column(name = "external_link")
    private String externalLink;

    @Column(name = "google_event_id")
    private String googleEventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MeetingStatus status = MeetingStatus.CONFIRMED;
}

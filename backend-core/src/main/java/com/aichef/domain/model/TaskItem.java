package com.aichef.domain.model;

import com.aichef.domain.enums.PriorityLevel;
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
@Table(name = "tasks")
public class TaskItem extends BaseEntity {

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PriorityLevel priority = PriorityLevel.MEDIUM;

    @Column(nullable = false)
    private boolean completed = false;

    @Column(name = "due_at")
    private OffsetDateTime dueAt;

    @Column(name = "google_task_id")
    private String googleTaskId;
}

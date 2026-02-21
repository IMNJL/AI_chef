package com.aichef.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "calendar_days", uniqueConstraints = {
        @UniqueConstraint(name = "uk_calendar_day_user_date", columnNames = {"user_id", "day_date"})
}, indexes = {
        @Index(name = "idx_calendar_days_user_date", columnList = "user_id,day_date")
})
public class CalendarDay extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "day_date", nullable = false)
    private LocalDate dayDate;

    @Column(name = "busy_level", nullable = false)
    private Integer busyLevel = 0;
}

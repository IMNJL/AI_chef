package com.aichef.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "user_profiles")
public class UserProfile {

    @Id
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "work_start_time", nullable = false)
    private LocalTime workStartTime = LocalTime.of(9, 0);

    @Column(name = "work_end_time", nullable = false)
    private LocalTime workEndTime = LocalTime.of(18, 0);

    @Column(name = "prefers_confirmation", nullable = false)
    private boolean prefersConfirmation = true;

    @Column(name = "verbosity_level", nullable = false)
    private String verbosityLevel = "concise";
}

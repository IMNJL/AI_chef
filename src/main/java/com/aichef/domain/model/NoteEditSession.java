package com.aichef.domain.model;

import com.aichef.service.NoteEditStep;
import com.aichef.service.NoteEditMode;
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
@Table(name = "note_edit_sessions")
public class NoteEditSession {

    @Id
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private NoteEditStep step;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NoteEditMode mode = NoteEditMode.EDIT;

    @Column(name = "target_note_id")
    private UUID targetNoteId;

    @Column(name = "target_note_number")
    private Integer targetNoteNumber;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PrePersist
    @PreUpdate
    public void touch() {
        this.updatedAt = OffsetDateTime.now();
    }
}

package com.aichef.domain.model;

import com.aichef.domain.enums.RelatedType;
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
@Table(name = "notifications", indexes = {
        @Index(name = "idx_notifications_due", columnList = "sent,notify_at")
})
public class Notification extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "related_type", nullable = false)
    private RelatedType relatedType;

    @Column(name = "related_id", nullable = false)
    private UUID relatedId;

    @Column(name = "notify_at", nullable = false)
    private OffsetDateTime notifyAt;

    @Column(nullable = false)
    private boolean sent = false;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;
}

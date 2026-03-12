package com.aichef.repository;

import com.aichef.domain.model.Notification;
import com.aichef.domain.enums.RelatedType;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    List<Notification> findTop100BySentFalseAndNotifyAtLessThanEqualOrderByNotifyAtAsc(OffsetDateTime now);

    @Modifying
    @Transactional
    void deleteByRelatedTypeAndRelatedId(RelatedType relatedType, UUID relatedId);
}

package com.aichef.repository;

import com.aichef.domain.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    List<Notification> findTop100BySentFalseAndNotifyAtLessThanEqualOrderByNotifyAtAsc(OffsetDateTime now);
}

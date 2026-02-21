package com.aichef.repository;

import com.aichef.domain.model.EventCreationSession;
import com.aichef.domain.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EventCreationSessionRepository extends JpaRepository<EventCreationSession, UUID> {
    Optional<EventCreationSession> findByUser(User user);
}

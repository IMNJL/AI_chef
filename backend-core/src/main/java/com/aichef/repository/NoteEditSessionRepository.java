package com.aichef.repository;

import com.aichef.domain.model.NoteEditSession;
import com.aichef.domain.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NoteEditSessionRepository extends JpaRepository<NoteEditSession, UUID> {
    Optional<NoteEditSession> findByUser(User user);
}

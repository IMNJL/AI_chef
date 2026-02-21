package com.aichef.repository;

import com.aichef.domain.model.Note;
import com.aichef.domain.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NoteRepository extends JpaRepository<Note, UUID> {
    List<Note> findTop20ByUserAndArchivedFalseOrderByUpdatedAtDesc(User user);

    Optional<Note> findByIdAndUser(UUID id, User user);
}

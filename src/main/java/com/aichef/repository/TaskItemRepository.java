package com.aichef.repository;

import com.aichef.domain.model.TaskItem;
import com.aichef.domain.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

public interface TaskItemRepository extends JpaRepository<TaskItem, UUID> {
    List<TaskItem> findByCalendarDay_UserAndCalendarDay_DayDateBetweenOrderByDueAtAsc(User user, LocalDate from, LocalDate to);

    List<TaskItem> findTop100ByCalendarDay_UserOrderByDueAtAsc(User user);

    Optional<TaskItem> findByIdAndCalendarDay_User(UUID id, User user);
}

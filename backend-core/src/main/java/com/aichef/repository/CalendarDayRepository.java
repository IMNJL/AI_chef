package com.aichef.repository;

import com.aichef.domain.model.CalendarDay;
import com.aichef.domain.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface CalendarDayRepository extends JpaRepository<CalendarDay, UUID> {
    Optional<CalendarDay> findByUserAndDayDate(User user, LocalDate dayDate);
}

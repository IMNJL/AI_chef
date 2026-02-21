package com.aichef.repository;

import com.aichef.domain.model.Meeting;
import com.aichef.domain.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface MeetingRepository extends JpaRepository<Meeting, UUID> {
    List<Meeting> findByCalendarDay_UserAndCalendarDay_DayDateBetweenOrderByStartsAtAsc(User user, LocalDate from, LocalDate to);

    List<Meeting> findByCalendarDay_UserOrderByStartsAtAsc(User user);
}

package com.projectardor.scheduling.advice.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.scheduling.advice.domain.CalendarAdvice;

public interface CalendarAdviceRepository extends JpaRepository<CalendarAdvice, UUID> {
}

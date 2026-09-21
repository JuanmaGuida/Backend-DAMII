package com.reclamos.backend.repository;

import com.reclamos.backend.entity.SatisfactionSurvey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SatisfactionSurveyRepository extends JpaRepository<SatisfactionSurvey, Long> {
    boolean existsByTicket_Id(UUID ticketId);
}
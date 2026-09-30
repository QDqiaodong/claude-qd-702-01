package com.metrology.lab.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metrology.lab.domain.OriginalRecordEvent;

public interface OriginalRecordEventRepository extends JpaRepository<OriginalRecordEvent, Long> {

    List<OriginalRecordEvent> findByOrderIdOrderByCreatedAtAscIdAsc(Long orderId);
}

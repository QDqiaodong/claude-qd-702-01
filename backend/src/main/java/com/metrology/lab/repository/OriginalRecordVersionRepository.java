package com.metrology.lab.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metrology.lab.domain.OriginalRecordVersion;

public interface OriginalRecordVersionRepository extends JpaRepository<OriginalRecordVersion, Long> {

    List<OriginalRecordVersion> findByOrderIdOrderByVersionNoAsc(Long orderId);

    Optional<OriginalRecordVersion> findFirstByOrderIdOrderByVersionNoDesc(Long orderId);

    Optional<OriginalRecordVersion> findByOrderIdAndVersionNo(Long orderId, Integer versionNo);

    boolean existsByOrderId(Long orderId);
}

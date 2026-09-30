package com.metrology.lab.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metrology.lab.domain.OriginalRecordItem;

public interface OriginalRecordItemRepository extends JpaRepository<OriginalRecordItem, Long> {

    List<OriginalRecordItem> findByVersionIdOrderByItemNoAscIdAsc(Long versionId);

    List<OriginalRecordItem> findByVersionIdInOrderByItemNoAscIdAsc(List<Long> versionIds);

    void deleteByVersionId(Long versionId);
}

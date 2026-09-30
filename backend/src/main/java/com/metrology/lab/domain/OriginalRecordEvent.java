package com.metrology.lab.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** 原始记录版本操作事件：只追加，不修改、不删除。 */
@Data
@Entity
@Table(name = "lab_record_event")
public class OriginalRecordEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "version_id", nullable = false)
    private Long versionId;

    /** DRAFT_SAVED / SUBMITTED / APPROVED / RETURNED / WITHDRAWN / REVISION_STARTED / MIGRATED / ASSIGNED */
    @Column(name = "event_type", nullable = false, length = 24)
    private String eventType;

    @Column(name = "actor_name", nullable = false, length = 32)
    private String actorName;

    /** VERIFIER / CHECKER / COORDINATOR */
    @Column(name = "actor_role", nullable = false, length = 16)
    private String actorRole;

    @Column(name = "detail", length = 300)
    private String detail;

    @Column(name = "from_version_id")
    private Long fromVersionId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}

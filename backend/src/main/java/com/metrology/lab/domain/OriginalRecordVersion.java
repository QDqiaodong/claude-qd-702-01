package com.metrology.lab.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 结构化原始记录版本。
 *
 * <p>提交后的版本只允许通过退回/撤回/修订复制出新版本，不允许原地更新。
 * 数据库另有 active_flag 唯一约束，保证同一委托至多一个可编辑草稿。
 */
@Data
@Entity
@Table(name = "lab_record_version")
public class OriginalRecordVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    /** DRAFT / SUBMITTED / RETURNED / CONFIRMED / SUPERSEDED / WITHDRAWN */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** NEW / MIGRATED / COPY_REJECTED / COPY_WITHDRAWN / COPY_REVISION */
    @Column(name = "source", nullable = false, length = 20)
    private String source;

    @Column(name = "source_version_id")
    private Long sourceVersionId;

    @Column(name = "legacy_source_text", length = 500)
    private String legacySourceText;

    @Column(name = "verifier_name", length = 32)
    private String verifierName;

    @Column(name = "checker_name", length = 32)
    private String checkerName;

    @Column(name = "conclusion", length = 16)
    private String conclusion;

    @Column(name = "record_remark", length = 200)
    private String recordRemark;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "reject_reason", length = 300)
    private String rejectReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    /** MySQL 由 schema.sql 生成；H2 测试库不使用该列参与写入。 */
    @Column(name = "active_flag", insertable = false, updatable = false)
    private Long activeFlag;
}

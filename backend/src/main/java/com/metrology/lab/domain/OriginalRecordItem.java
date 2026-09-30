package com.metrology.lab.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** 原始记录版本内的逐项校准数据。版本提交后随版本整体冻结。 */
@Data
@Entity
@Table(name = "lab_record_item")
public class OriginalRecordItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version_id", nullable = false)
    private Long versionId;

    @Column(name = "item_no", nullable = false)
    private Integer itemNo;

    @Column(name = "calibration_point", nullable = false, length = 80)
    private String calibrationPoint;

    @Column(name = "standard_value", nullable = false, length = 80)
    private String standardValue;

    @Column(name = "indicated_value", nullable = false, length = 80)
    private String indicatedValue;

    @Column(name = "error_value", nullable = false, length = 80)
    private String errorValue;

    @Column(name = "unit", nullable = false, length = 24)
    private String unit;

    @Column(name = "remark", length = 120)
    private String remark;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}

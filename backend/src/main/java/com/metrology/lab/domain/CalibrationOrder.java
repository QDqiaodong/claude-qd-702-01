package com.metrology.lab.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 检定委托与流转单。
 *
 * <p>状态沿 {@link MetroDict#FLOW_ORDER} 单向推进（可回退）：
 * 收样 RECEIVED → 检定中 CALIBRATING → 核验 VERIFYING → 出证 CERTIFYING → 已发放 ISSUED。
 * {@code instNo} 是器具编号快照，用于演示「按自然主键建委托单」。
 */
@Data
@Entity
@Table(name = "lab_calibration_order")
public class CalibrationOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 委托单号 */
    @Column(name = "order_no", nullable = false, length = 32)
    private String orderNo;

    @Column(name = "client_id")
    private Long clientId;

    @Column(name = "instrument_id")
    private Long instrumentId;

    /** 器具编号快照 */
    @Column(name = "inst_no", length = 32)
    private String instNo;

    @Column(name = "procedure_id")
    private Long procedureId;

    @Column(name = "receive_date")
    private LocalDate receiveDate;

    /** 检定员 */
    @Column(name = "verifier", length = 32)
    private String verifier;

    /** 核验员 */
    @Column(name = "checker", length = 32)
    private String checker;

    /** 实测数据项：多条用 ; 分隔，单条为 校准点|标准值|示值|示值误差 */
    @Column(name = "measured_data", length = 500)
    private String measuredData;

    /** PASS 合格 / LIMITED 限用 / FAIL 不合格 */
    @Column(name = "conclusion", length = 16)
    private String conclusion;

    @Column(name = "cert_no", length = 48)
    private String certNo;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "cert_valid_until")
    private LocalDate certValidUntil;

    /** 本次收费（元） */
    @Column(name = "fee", precision = 10, scale = 2)
    private BigDecimal fee;

    @Column(name = "remark", length = 200)
    private String remark;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

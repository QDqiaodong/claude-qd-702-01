package com.metrology.lab.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 出参视图。
 *
 * <p>全部用 record：只在 service 里构造、直接序列化给前端，不需要 setter。
 * 派生字段（到期天数、流转序号、证书剩余天数）一律在 service 里算好，前端只负责着色展示。
 */
public final class Views {

    private Views() {
    }

    /** 模块一：送检客户 */
    public record ClientView(
            Long id,
            String code,
            String name,
            String creditCode,
            String contactName,
            String phone,
            String address,
            String settleType,
            String settleTypeName,
            Integer settleDays,
            String remark,
            LocalDate createdAt,
            Integer instrumentCount,
            Long orderCount) {
    }

    /** 客户抽屉里的历史委托单 */
    public record ClientOrderView(
            Long id,
            String orderNo,
            String instNo,
            String instrumentName,
            String procNo,
            LocalDate receiveDate,
            String status,
            String statusName,
            String conclusion,
            String conclusionName,
            String certNo,
            BigDecimal fee) {
    }

    /** 模块二：计量器具台账 */
    public record InstrumentView(
            Long id,
            String instNo,
            String name,
            String modelSpec,
            String measureRange,
            String accuracyGrade,
            String manufacturer,
            String factoryNo,
            Integer cycleMonths,
            LocalDate lastCalDate,
            LocalDate nextDueDate,
            String status,
            String statusName,
            Long clientId,
            String clientName,
            /** 距下次到期日的天数，null 表示未排期 */
            Integer dueDays,
            /** true = 已超期（dueDays < 0），需立即停用补检 */
            boolean overdue,
            /** true = 30 天内到期，台账表格标红 */
            boolean dueWarn,
            String remark,
            LocalDateTime updatedAt) {
    }

    /** 模块三：检定规程 */
    public record ProcedureView(
            Long id,
            String procNo,
            String name,
            String category,
            String standardNo,
            String deviceReq,
            String envCondition,
            BigDecimal unitFee,
            String remark,
            Long orderCount) {
    }

    /** 模块四：检定委托与流转单 */
    public record OrderView(
            Long id,
            String orderNo,
            Long clientId,
            String clientName,
            Long instrumentId,
            String instNo,
            String instrumentName,
            String modelSpec,
            String measureRange,
            String accuracyGrade,
            String manufacturer,
            String factoryNo,
            Long procedureId,
            String procNo,
            String procedureName,
            BigDecimal unitFee,
            LocalDate receiveDate,
            String verifier,
            String checker,
            String measuredData,
            String conclusion,
            String conclusionName,
            String certNo,
            String status,
            String statusName,
            /** 流转序号：0 收样 … 4 已发放；作废返回 -1 */
            Integer stepIndex,
            LocalDate certValidUntil,
            BigDecimal fee,
            String remark,
            LocalDateTime updatedAt,
            /** 当前挂接（占用中）的标准器数量 */
            Integer deviceCount,
            /** 当前挂接标准器中最早的证书到期日（列表徽标用） */
            LocalDate earliestDeviceValidUntil,
            /** 按当前时间实时判定：存在不可放行的标准器（停用/送检/过期/记录异常） */
            boolean deviceBlocked,
            /** 不可放行的逐条原因（点名到具体标准器） */
            List<String> blockReasons,
            /** 是否允许调整挂接清单：仅收样态可改 */
            boolean devicesEditable,
            /** 详情：当前挂接标准器的实时状态（列表接口为空，仅详情返回） */
            List<OrderDeviceView> devices,
            /** 详情：已释放的挂接历史留痕（解除/替换/回退/作废/发放） */
            List<DeviceLinkHistoryView> deviceHistory,
            /** 详情：原始记录版本、明细、事件、旧文本与当前操作人权限；列表接口为空 */
            RecordBundleView records) {
    }

    /** 委托上当前挂接的标准器（状态/有效期按查询当下实时计算） */
    public record OrderDeviceView(
            Long linkId,
            Long deviceId,
            String deviceNo,
            String name,
            String model,
            /** 标准器当前证书有效期至（实时，不是快照） */
            LocalDate certValidUntil,
            /** 挂接当时的证书有效期至快照 */
            LocalDate certValidSnapshot,
            String status,
            String statusName,
            /** 证书剩余天数，负数表示已过期 */
            Integer remainDays,
            boolean expired,
            boolean expiring,
            /** 在用且证书未过期，参与本次检定放行无问题 */
            boolean usable,
            /** 不可放行原因（点名），usable=true 时为空 */
            List<String> issues) {
    }

    /** 已释放的挂接历史 */
    public record DeviceLinkHistoryView(
            Long deviceId,
            String deviceNo,
            String name,
            LocalDateTime linkedAt,
            LocalDate certValidSnapshot,
            LocalDateTime releasedAt,
            String releasedReason,
            String releasedReasonName) {
    }

    /** 挂接选择器中的候选标准器（带实时占用冲突与可用判定） */
    public record DeviceOptionView(
            Long id,
            String deviceNo,
            String name,
            String model,
            String measureRange,
            LocalDate certValidUntil,
            String status,
            String statusName,
            Integer remainDays,
            boolean expired,
            boolean expiring,
            /** 在用且证书有效（仍允许挂接，但挂后会被放行校验拦住的设备不置灰） */
            boolean usable,
            /** 已被本笔委托选中 */
            boolean selected,
            /** 被其他未完成委托占用（不可抢占，选择器置灰） */
            boolean busy,
            Long busyOrderId,
            String busyOrderNo,
            /** 不可用/占用的一句话说明 */
            String issue) {
    }

    /** 左侧待办队列里的单条 */
    public record QueueItemView(
            Long id,
            String orderNo,
            String instNo,
            String instrumentName,
            String clientName,
            LocalDate receiveDate,
            Integer daysWaiting,
            /** 标准器挂接数量 */
            Integer deviceCount,
            /** 最早证书到期日 */
            LocalDate earliestDeviceValidUntil,
            /** 当前存在不可放行的标准器 */
            boolean deviceBlocked) {
    }

    /** 左侧待办队列：按流转状态分组 */
    public record QueueGroupView(
            String status,
            String statusName,
            Integer count,
            List<QueueItemView> items) {
    }

    /** 顶部概览：全站计数器 */
    public record OverviewView(
            Long instrumentTotal,
            Long instrumentDueSoon,
            Long instrumentOverdue,
            Long orderTotal,
            Long orderOpen,
            Long deviceExpiring,
            BigDecimal monthFee) {
    }

    /** 原始记录明细项 */
    public record RecordItemView(
            Long id,
            Integer itemNo,
            String rowKey,
            String calibrationPoint,
            String standardValue,
            String indicatedValue,
            String errorValue,
            String unit,
            String remark) {
    }

    /** 原始记录版本留痕 */
    public record RecordVersionView(
            Long id,
            Integer versionNo,
            String status,
            String statusName,
            String source,
            String sourceName,
            Long sourceVersionId,
            Integer sourceVersionNo,
            String legacySourceText,
            boolean legacy,
            String verifierName,
            String checkerName,
            String conclusion,
            String conclusionName,
            String recordRemark,
            LocalDateTime submittedAt,
            LocalDateTime reviewedAt,
            String rejectReason,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime lockedAt,
            boolean editable,
            boolean locked,
            boolean current,
            boolean certificateVersion,
            Integer itemCount,
            List<RecordItemView> items,
            /** 与上一版本逐行逐字段对比；详情的当前版本才返回，历史列表可能为空 */
            List<RecordDiffView> changes) {
    }

    /** 版本差异：按行对齐后逐字段标识 SAME/MODIFIED/ADDED/REMOVED */
    public record RecordDiffView(
            Integer itemNo,
            String changeType,
            String changeTypeName,
            RecordItemView before,
            RecordItemView after,
            List<RecordFieldDiffView> fields) {
    }

    public record RecordFieldDiffView(
            String field,
            String fieldName,
            String before,
            String after,
            boolean changed) {
    }

    /** 原始记录事件流 */
    public record RecordEventView(
            Long id,
            Long versionId,
            Integer versionNo,
            String eventType,
            String eventTypeName,
            String actorName,
            String actorRole,
            String actorRoleName,
            String detail,
            Long fromVersionId,
            Integer fromVersionNo,
            LocalDateTime createdAt) {
    }

    /** 当前登录操作者（演示环境由网关注入请求头；服务端做最终角色和指派校验） */
    public record OperatorView(String name, String role, String roleName, boolean authenticated) {
    }

    /** 委托详情中的原始记录聚合视图 */
    public record RecordBundleView(
            String mode,
            String modeName,
            boolean structured,
            boolean legacyText,
            boolean legacyReadonly,
            String legacyText,
            boolean canMigrateLegacy,
            Long draftVersionId,
            Long currentVersionId,
            Integer currentVersionNo,
            Long certificateVersionId,
            Integer certificateVersionNo,
            String certificateDataSource,
            String certificateDataSourceName,
            String certificateConclusion,
            String certificateConclusionName,
            List<RecordItemView> certificateItems,
            List<RecordVersionView> versions,
            List<RecordEventView> events,
            OperatorView operator,
            boolean canSaveDraft,
            boolean canSubmit,
            boolean canReturn,
            boolean canApprove,
            boolean canWithdraw,
            boolean canStartRevision,
            boolean canAssign,
            boolean canMigrate,
            String actionHint) {
    }

    /** 模块五：标准器 */
    public record DeviceView(
            Long id,
            String deviceNo,
            String name,
            String model,
            String measureRange,
            String traceOrg,
            LocalDate lastTraceDate,
            LocalDate certValidUntil,
            String status,
            String statusName,
            /** 证书剩余天数，负数表示已过期 */
            Integer remainDays,
            boolean expired,
            boolean expiring,
            /** 当前是否被未完成委托占用 */
            boolean busy,
            /** 占用方委托单号（空 = 未占用） */
            String busyOrderNo,
            /** 证书可修订（录入新的有效期），停用/溯源回来后可重新放行 */
            String remark) {
    }
}

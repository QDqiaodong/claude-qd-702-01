package com.metrology.lab.domain;

import java.util.List;
import java.util.Map;

/**
 * 计量检定所词典与业务规则。
 *
 * <p>状态字典、收费与流转规则集中在此，便于统一调整。
 * 注意：本仓不使用 common.Dict（那份是别处的模板），业务字典全部在本类中定义。
 */
public final class MetroDict {

    private MetroDict() {
    }

    // ---------------- 器具状态 ----------------
    private static final Map<String, String> INSTRUMENT_STATUS = Map.of(
            "IN_USE", "在用",
            "STOPPED", "停用",
            "IN_CALIBRATION", "送检中",
            "SCRAPPED", "报废");

    // ---------------- 委托单流转状态（严格顺序） ----------------
    // VOIDED（作废）是终态，不参与推进顺序，仅用于作废后释放占用、保留历史可查。
    public static final List<String> FLOW_ORDER = List.of(
            "RECEIVED", "CALIBRATING", "VERIFYING", "CERTIFYING", "ISSUED");

    public static final String STATUS_VOIDED = "VOIDED";

    private static final Map<String, String> FLOW_STATUS = Map.of(
            "RECEIVED", "收样",
            "CALIBRATING", "检定中",
            "VERIFYING", "核验",
            "CERTIFYING", "出证",
            "ISSUED", "已发放",
            "VOIDED", "已作废");

    // ---------------- 检定结论 ----------------
    private static final Map<String, String> CONCLUSION = Map.of(
            "PASS", "合格",
            "LIMITED", "限用",
            "FAIL", "不合格");

    // ---------------- 结算方式 ----------------
    private static final Map<String, String> SETTLE_TYPE = Map.of(
            "CASH", "现结",
            "MONTHLY", "月结");

    // ---------------- 原始记录版本 ----------------
    public static final String RECORD_DRAFT = "DRAFT";
    public static final String RECORD_SUBMITTED = "SUBMITTED";
    public static final String RECORD_RETURNED = "RETURNED";
    public static final String RECORD_CONFIRMED = "CONFIRMED";
    public static final String RECORD_SUPERSEDED = "SUPERSEDED";
    public static final String RECORD_WITHDRAWN = "WITHDRAWN";

    private static final Map<String, String> RECORD_STATUS = Map.of(
            RECORD_DRAFT, "草稿",
            RECORD_SUBMITTED, "待核验",
            RECORD_RETURNED, "已退回锁定",
            RECORD_CONFIRMED, "已确认",
            RECORD_SUPERSEDED, "已被替代",
            RECORD_WITHDRAWN, "已撤回锁定");

    private static final Map<String, String> RECORD_SOURCE = Map.of(
            "NEW", "新建",
            "MIGRATED", "旧文本转换",
            "COPY_REJECTED", "退回复制",
            "COPY_WITHDRAWN", "误提交撤回复制",
            "COPY_REVISION", "修订复制");

    private static final Map<String, String> RECORD_EVENT = Map.of(
            "DRAFT_SAVED", "保存草稿",
            "SUBMITTED", "提交复核",
            "APPROVED", "核验通过",
            "RETURNED", "核验退回",
            "WITHDRAWN", "撤回提交",
            "REVISION_STARTED", "发起修订",
            "MIGRATED", "旧文本转换",
            "ASSIGNED", "人员调整");

    private static final Map<String, String> ACTOR_ROLE = Map.of(
            "VERIFIER", "检定员",
            "CHECKER", "核验员",
            "COORDINATOR", "调度管理员");

    public static final String MODE_LEGACY_TEXT = "LEGACY_TEXT";
    public static final String MODE_STRUCTURED = "STRUCTURED";
    public static final String MODE_LEGACY_READONLY = "LEGACY_READONLY";

    private static final Map<String, String> RECORD_MODE = Map.of(
            MODE_LEGACY_TEXT, "旧文本记录",
            MODE_STRUCTURED, "结构化原始记录",
            MODE_LEGACY_READONLY, "已核验旧文本只读");

    // ---------------- 标准器状态 ----------------
    private static final Map<String, String> DEVICE_STATUS = Map.of(
            "IN_USE", "在用",
            "STOPPED", "停用",
            "TRACING", "送检");

    /** 到期预警阈值：器具距下次到期日不足 30 天即预警 */
    public static final int INSTRUMENT_DUE_WARN_DAYS = 30;

    /** 标准器证书有效期不足 60 天即需要安排溯源 */
    public static final int DEVICE_VALID_WARN_DAYS = 60;

    // ---------------- 挂接释放原因 ----------------
    /** 收样态手动解除 */
    public static final String RELEASE_DETACHED = "DETACHED";
    /** 收样态被别的标准器替换 */
    public static final String RELEASE_REPLACED = "REPLACED";
    /** 从检定中退回收样，未真正使用，占用释放允许重新选择 */
    public static final String RELEASE_ROLLBACK = "ROLLBACK_RECEIVED";
    /** 委托作废 */
    public static final String RELEASE_VOIDED = "VOIDED";
    /** 最终发放，占用释放但挂接事实留痕 */
    public static final String RELEASE_ISSUED = "ISSUED";

    private static final Map<String, String> RELEASE_REASON = Map.of(
            RELEASE_DETACHED, "收样解除",
            RELEASE_REPLACED, "收样替换",
            RELEASE_ROLLBACK, "退回收样释放",
            RELEASE_VOIDED, "委托作废释放",
            RELEASE_ISSUED, "发放留痕");

    /** 允许调整挂接清单的唯一状态：尚未开始检定 */
    public static boolean canEditDevices(String orderStatus) {
        return "RECEIVED".equals(orderStatus);
    }

    public static String releaseReasonName(String reason) {
        return reason == null ? "" : RELEASE_REASON.getOrDefault(reason, reason);
    }

    public static boolean isValidInstrumentStatus(String status) {
        return status != null && INSTRUMENT_STATUS.containsKey(status);
    }

    public static String instrumentStatusName(String status) {
        return status == null ? "" : INSTRUMENT_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidFlowStatus(String status) {
        return status != null && FLOW_STATUS.containsKey(status);
    }

    public static String flowStatusName(String status) {
        return status == null ? "" : FLOW_STATUS.getOrDefault(status, status);
    }

    public static int flowIndex(String status) {
        int index = FLOW_ORDER.indexOf(status);
        return index < 0 ? 0 : index;
    }

    public static boolean isValidConclusion(String conclusion) {
        return conclusion != null && CONCLUSION.containsKey(conclusion);
    }

    public static String conclusionName(String conclusion) {
        return conclusion == null ? "" : CONCLUSION.getOrDefault(conclusion, conclusion);
    }

    public static String recordStatusName(String status) {
        return status == null ? "" : RECORD_STATUS.getOrDefault(status, status);
    }

    public static String recordSourceName(String source) {
        return source == null ? "" : RECORD_SOURCE.getOrDefault(source, source);
    }

    public static String recordEventName(String eventType) {
        return eventType == null ? "" : RECORD_EVENT.getOrDefault(eventType, eventType);
    }

    public static String actorRoleName(String role) {
        return role == null ? "" : ACTOR_ROLE.getOrDefault(role, role);
    }

    public static String recordModeName(String mode) {
        return mode == null ? "" : RECORD_MODE.getOrDefault(mode, mode);
    }

    public static boolean isEditableRecord(String status) {
        return RECORD_DRAFT.equals(status);
    }

    public static boolean isValidSettleType(String type) {
        return type != null && SETTLE_TYPE.containsKey(type);
    }

    public static String settleTypeName(String type) {
        return type == null ? "" : SETTLE_TYPE.getOrDefault(type, type);
    }

    public static boolean isValidDeviceStatus(String status) {
        return status != null && DEVICE_STATUS.containsKey(status);
    }

    public static String deviceStatusName(String status) {
        return status == null ? "" : DEVICE_STATUS.getOrDefault(status, status);
    }
}

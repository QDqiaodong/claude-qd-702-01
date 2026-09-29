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

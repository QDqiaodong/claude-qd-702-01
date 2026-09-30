package com.metrology.lab.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 结构化原始记录模块入参。 */
public final class OriginalRecordDtos {

    private OriginalRecordDtos() {
    }

    @Data
    public static class RecordItemReq {
        /** 前端临时行号；服务端最终会重新编号 */
        private String rowKey;
        @NotBlank(message = "校准点不能为空")
        private String calibrationPoint;
        @NotBlank(message = "标准值不能为空")
        private String standardValue;
        @NotBlank(message = "示值不能为空")
        private String indicatedValue;
        @NotBlank(message = "误差不能为空")
        private String errorValue;
        @NotBlank(message = "单位不能为空")
        private String unit;
        private String remark;
    }

    /** 保存草稿；空清单允许先保存，但提交时必须至少一条且字段完整。 */
    @Data
    public static class DraftSaveReq {
        @Valid
        private List<RecordItemReq> items;
        /** PASS / LIMITED / FAIL；草稿可空 */
        private String conclusion;
        private String recordRemark;
    }

    @Data
    public static class ReviewReq {
        @NotBlank(message = "退回原因不能为空")
        private String reason;
    }

    @Data
    public static class RevisionReq {
        @NotBlank(message = "发起修订必须写明原因")
        private String reason;
    }

    @Data
    public static class AssignReq {
        private String verifier;
        private String checker;
        @NotBlank(message = "人员调整必须写明原因")
        private String reason;
    }

    @Data
    public static class MigrateLegacyReq {
        @NotBlank(message = "转换旧文本必须由被指派检定员或调度管理员确认")
        private String confirmedBy;
    }
}

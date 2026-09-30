package com.metrology.lab.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.metrology.lab.common.ApiResponse;
import com.metrology.lab.dto.CalibrationOrderDtos;
import com.metrology.lab.dto.OriginalRecordDtos;
import com.metrology.lab.dto.Views;
import com.metrology.lab.service.CalibrationOrderService;
import com.metrology.lab.service.OriginalRecordService;

import jakarta.validation.Valid;

/**
 * 模块四：检定委托与流转单 —— 全站主业务接口。
 *
 * <p>{@code POST /api/calibration-orders} 用<b>器具编号</b>入参，服务端通过 {@code @NaturalId}
 * 自然主键加载器具后建单，流转状态由 {@code advance / rollback} 推进。
 */
@RestController
@RequestMapping("/api/calibration-orders")
public class CalibrationOrderController {

    private final CalibrationOrderService orderService;
    private final OriginalRecordService recordService;

    public CalibrationOrderController(CalibrationOrderService orderService,
                                      OriginalRecordService recordService) {
        this.orderService = orderService;
        this.recordService = recordService;
    }

    @GetMapping
    public ApiResponse<List<Views.OrderView>> list(@RequestParam(required = false) String status,
                                                   @RequestParam(required = false) Long clientId) {
        return ApiResponse.success(orderService.list(status, clientId));
    }

    /** 左侧常驻「待办队列」：按流转状态分组 */
    @GetMapping("/queue")
    public ApiResponse<List<Views.QueueGroupView>> queue() {
        return ApiResponse.success(orderService.queue());
    }

    /** 顶部概览计数器 */
    @GetMapping("/overview")
    public ApiResponse<Views.OverviewView> overview() {
        return ApiResponse.success(orderService.overview());
    }

    @GetMapping("/{id}")
    public ApiResponse<Views.OrderView> detail(@PathVariable Long id,
                                               @RequestHeader(value = "X-Operator-Name", required = false)
                                               String operatorName) {
        return ApiResponse.success(orderService.detail(id, operatorName));
    }

    /** 当前操作者（演示环境由页面选择，真实环境应由认证网关注入） */
    @GetMapping("/operators/me")
    public ApiResponse<Views.OperatorView> me(@RequestHeader(value = "X-Operator-Name", required = false)
                                              String operatorName) {
        return ApiResponse.success(recordService.currentOperator(operatorName));
    }

    /** 【@NaturalId】按器具编号建委托单 */
    @PostMapping
    public ApiResponse<Views.OrderView> create(@RequestBody @Valid CalibrationOrderDtos.OrderSaveReq req) {
        return ApiResponse.success("委托单已受理，流转状态：收样", orderService.create(req));
    }

    @PostMapping("/{id}/result")
    public ApiResponse<Views.OrderView> result(@PathVariable Long id,
                                               @RequestBody CalibrationOrderDtos.ResultReq req) {
        return ApiResponse.success("实测数据与结论已保存", orderService.saveResult(id, req));
    }

    @PostMapping("/{id}/advance")
    public ApiResponse<Views.OrderView> advance(@PathVariable Long id) {
        return ApiResponse.success("流转已推进一格", orderService.advance(id));
    }

    @PostMapping("/{id}/rollback")
    public ApiResponse<Views.OrderView> rollback(@PathVariable Long id) {
        return ApiResponse.success("流转已回退一格", orderService.rollback(id));
    }

    /** 作废委托（未发放均可），已挂标准器随之释放 */
    @PostMapping("/{id}/void")
    public ApiResponse<Views.OrderView> voidOrder(@PathVariable Long id) {
        return ApiResponse.success("委托已作废，标准器占用已释放", orderService.voidOrder(id));
    }

    // ============================ 结构化原始记录与版本留痕 ============================

    @PostMapping("/{id}/records/draft")
    public ApiResponse<Views.RecordBundleView> createDraft(
            @PathVariable Long id,
            @RequestHeader(value = "X-Operator-Name", required = false) String operatorName) {
        return ApiResponse.success("已建立结构化原始记录草稿", recordService.createDraft(id, operatorName));
    }

    @PutMapping("/{id}/records/draft")
    public ApiResponse<Views.RecordBundleView> saveDraft(
            @PathVariable Long id,
            @RequestBody(required = false) @Valid OriginalRecordDtos.DraftSaveReq req,
            @RequestHeader(value = "X-Operator-Name", required = false) String operatorName) {
        return ApiResponse.success("草稿已保存，尚未锁定", recordService.saveDraft(id, req, operatorName));
    }

    @PostMapping("/{id}/records/submit")
    public ApiResponse<Views.RecordBundleView> submit(
            @PathVariable Long id,
            @RequestBody(required = false) @Valid OriginalRecordDtos.DraftSaveReq req,
            @RequestHeader(value = "X-Operator-Name", required = false) String operatorName) {
        return ApiResponse.success("本版本已提交复核并锁定", recordService.submit(id, req, operatorName));
    }

    @PostMapping("/{id}/records/approve")
    public ApiResponse<Views.RecordBundleView> approve(
            @PathVariable Long id,
            @RequestHeader(value = "X-Operator-Name", required = false) String operatorName) {
        return ApiResponse.success("核验通过，证书已固定引用该确认版本", recordService.approve(id, operatorName));
    }

    @PostMapping("/{id}/records/return")
    public ApiResponse<Views.RecordBundleView> returnRecord(
            @PathVariable Long id,
            @RequestBody @Valid OriginalRecordDtos.ReviewReq req,
            @RequestHeader(value = "X-Operator-Name", required = false) String operatorName) {
        return ApiResponse.success("已写明原因退回，并生成新版本草稿",
                recordService.returnVersion(id, req, operatorName));
    }

    @PostMapping("/{id}/records/withdraw")
    public ApiResponse<Views.RecordBundleView> withdraw(
            @PathVariable Long id,
            @RequestBody(required = false) OriginalRecordDtos.RevisionReq req,
            @RequestHeader(value = "X-Operator-Name", required = false) String operatorName) {
        return ApiResponse.success("已撤回误提交版本，并复制生成新草稿",
                recordService.withdraw(id, operatorName, req == null ? null : req.getReason()));
    }

    @PostMapping("/{id}/records/revision")
    public ApiResponse<Views.RecordBundleView> revision(
            @PathVariable Long id,
            @RequestBody @Valid OriginalRecordDtos.RevisionReq req,
            @RequestHeader(value = "X-Operator-Name", required = false) String operatorName) {
        return ApiResponse.success("已保留旧确认版，并复制生成修订草稿",
                recordService.startRevision(id, req, operatorName));
    }

    @PutMapping("/{id}/records/assignment")
    public ApiResponse<Views.RecordBundleView> assign(
            @PathVariable Long id,
            @RequestBody @Valid OriginalRecordDtos.AssignReq req,
            @RequestHeader(value = "X-Operator-Name", required = false) String operatorName) {
        return ApiResponse.success("人员调整已记录", recordService.assign(id, req, operatorName));
    }

    @PostMapping("/{id}/records/migrate-legacy")
    public ApiResponse<Views.RecordBundleView> migrateLegacy(
            @PathVariable Long id,
            @RequestBody(required = false) @Valid OriginalRecordDtos.MigrateLegacyReq req,
            @RequestHeader(value = "X-Operator-Name", required = false) String operatorName) {
        String actor = req == null ? operatorName : req.getConfirmedBy();
        return ApiResponse.success("旧文本已转换为第一版结构化草稿，原文继续保留为历史记录",
                recordService.migrateLegacy(id, actor));
    }

    /** 挂接选择器：全部标准器 + 实时占用冲突 + 本笔已选 */
    @GetMapping("/{id}/device-options")
    public ApiResponse<List<Views.DeviceOptionView>> deviceOptions(@PathVariable Long id) {
        return ApiResponse.success(orderService.deviceOptions(id));
    }

    /**
     * 保存挂接清单（挂接 / 替换 / 解除同一入口，收样态可用）。
     * 最终是否允许由服务端判定：占用冲突、标准器失效都在这一关拦住。
     */
    @PutMapping("/{id}/devices")
    public ApiResponse<Views.OrderView> saveDevices(@PathVariable Long id,
                                                    @RequestBody CalibrationOrderDtos.DeviceLinkReq req) {
        return ApiResponse.success("标准器挂接清单已保存", orderService.saveDevices(id, req));
    }
}

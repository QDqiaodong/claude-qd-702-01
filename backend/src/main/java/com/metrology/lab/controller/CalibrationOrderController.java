package com.metrology.lab.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.metrology.lab.common.ApiResponse;
import com.metrology.lab.dto.CalibrationOrderDtos;
import com.metrology.lab.dto.Views;
import com.metrology.lab.service.CalibrationOrderService;

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

    public CalibrationOrderController(CalibrationOrderService orderService) {
        this.orderService = orderService;
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
    public ApiResponse<Views.OrderView> detail(@PathVariable Long id) {
        return ApiResponse.success(orderService.detail(id));
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

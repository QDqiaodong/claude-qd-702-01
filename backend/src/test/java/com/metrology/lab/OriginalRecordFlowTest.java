package com.metrology.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.metrology.lab.domain.Client;
import com.metrology.lab.domain.Instrument;
import com.metrology.lab.domain.Procedure;
import com.metrology.lab.domain.StandardDevice;
import com.metrology.lab.dto.OriginalRecordDtos;
import com.metrology.lab.dto.Views;
import com.metrology.lab.repository.CalibrationOrderRepository;
import com.metrology.lab.repository.ClientRepository;
import com.metrology.lab.repository.InstrumentRepository;
import com.metrology.lab.repository.ProcedureRepository;
import com.metrology.lab.repository.StandardDeviceRepository;
import com.metrology.lab.service.CalibrationOrderService;
import com.metrology.lab.service.OriginalRecordService;

/**
 * 结构化原始记录：草稿锁定、退回新版本、角色分离、证书固定与旧文本兼容的服务级冒烟。
 */
@SpringBootTest
class OriginalRecordFlowTest {

    @Autowired OriginalRecordService recordService;
    @Autowired CalibrationOrderService orderService;
    @Autowired CalibrationOrderRepository orderRepository;
    @Autowired ClientRepository clientRepository;
    @Autowired InstrumentRepository instrumentRepository;
    @Autowired ProcedureRepository procedureRepository;
    @Autowired StandardDeviceRepository deviceRepository;
    @Autowired JdbcTemplate jdbc;

    private Long deviceId;

    @BeforeEach
    void seed() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String t : List.of("lab_record_event", "lab_record_item", "lab_record_version",
                "lab_order_device", "lab_calibration_order", "lab_standard_device",
                "lab_instrument", "lab_procedure", "lab_client")) {
            jdbc.execute("DELETE FROM " + t);
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");

        Client c = new Client();
        c.setCode("C-R"); c.setName("记录客户"); c.setCreditCode("R-1");
        c.setSettleType("CASH"); c.setSettleDays(0);
        clientRepository.save(c);

        Procedure p = new Procedure();
        p.setProcNo("JJG-R"); p.setName("记录规程"); p.setCategory("测试");
        p.setUnitFee(BigDecimal.TEN);
        procedureRepository.save(p);

        Instrument in = new Instrument();
        in.setInstNo("JLB-R1"); in.setName("记录器具"); in.setStatus("IN_USE");
        in.setClientId(c.getId()); in.setCycleMonths(12);
        instrumentRepository.save(in);

        StandardDevice d = new StandardDevice();
        d.setDeviceNo("STD-R1"); d.setName("记录标准器"); d.setStatus("IN_USE");
        d.setCertValidUntil(LocalDate.now().plusYears(1));
        deviceId = deviceRepository.save(d).getId();
    }

    private Long createOrder(String no) {
        var req = new com.metrology.lab.dto.CalibrationOrderDtos.OrderSaveReq();
        req.setOrderNo(no);
        req.setInstNo("JLB-R1");
        req.setProcNo("JJG-R");
        req.setVerifier("赵砚青");
        req.setChecker("孙敬之");
        Long id = orderService.create(req).id();
        var link = new com.metrology.lab.dto.CalibrationOrderDtos.DeviceLinkReq();
        link.setDeviceIds(List.of(deviceId));
        orderService.saveDevices(id, link);
        orderService.advance(id); // CALIBRATING
        return id;
    }

    private OriginalRecordDtos.DraftSaveReq draftReq() {
        OriginalRecordDtos.RecordItemReq item = new OriginalRecordDtos.RecordItemReq();
        item.setCalibrationPoint("10V");
        item.setStandardValue("+10.000V");
        item.setIndicatedValue("+10.002V");
        item.setErrorValue("+0.002V");
        item.setUnit("V");
        item.setRemark("备注");
        OriginalRecordDtos.DraftSaveReq req = new OriginalRecordDtos.DraftSaveReq();
        req.setItems(List.of(item));
        req.setConclusion("PASS");
        return req;
    }

    @Test
    void submitReturnCreatesNewDraftAndKeepsHistory() {
        Long id = createOrder("WT-R1");
        recordService.createDraft(id, "赵砚青");
        recordService.saveDraft(id, draftReq(), "赵砚青");
        recordService.submit(id, draftReq(), "赵砚青");
        Views.RecordBundleView bundle = recordService.bundle(id, "赵砚青");
        assertEquals(1, bundle.versions().size());
        assertEquals("SUBMITTED", bundle.versions().get(0).status());

        OriginalRecordDtos.ReviewReq review = new OriginalRecordDtos.ReviewReq();
        review.setReason("单位需复核");
        recordService.returnVersion(id, review, "孙敬之");

        bundle = recordService.bundle(id, "赵砚青");
        assertEquals(2, bundle.versions().size());
        assertEquals("RETURNED", bundle.versions().get(0).status());
        assertEquals("DRAFT", bundle.versions().get(1).status());
        assertEquals("单位需复核", bundle.versions().get(0).rejectReason());
        assertEquals("CALIBRATING", orderService.detail(id).status());
    }

    @Test
    void samePersonCannotApproveAndUnassignedCannotOperate() {
        Long id = createOrder("WT-R2");
        recordService.createDraft(id, "赵砚青");
        recordService.submit(id, draftReq(), "赵砚青");
        // 提交人不能当核验员
        assertThrows(RuntimeException.class, () -> recordService.approve(id, "赵砚青"));
        // 未被指派的检定员/核验员不能代操作
        assertThrows(RuntimeException.class, () -> recordService.approve(id, "王核验"));
        // 正确核验员通过
        recordService.approve(id, "孙敬之");
        Views.RecordBundleView bundle = recordService.bundle(id, "孙敬之");
        assertEquals(1, bundle.currentVersionNo());
        assertEquals(1, bundle.certificateVersionNo());
    }

    @Test
    void revisionDoesNotSilentlyChangeIssuedCertificate() {
        Long id = createOrder("WT-R3");
        recordService.createDraft(id, "赵砚青");
        recordService.submit(id, draftReq(), "赵砚青");
        recordService.approve(id, "孙敬之");
        Long fixed = recordService.bundle(id, "孙敬之").certificateVersionId();

        // 证书编号后发放：证书固定引用第 1 版
        var result = new com.metrology.lab.dto.CalibrationOrderDtos.ResultReq();
        result.setCertNo("CERT-R3");
        orderService.saveResult(id, result);
        orderService.advance(id); // ISSUED
        assertEquals(fixed, orderService.detail(id).records().certificateVersionId());

        // 已发放不能修订/改数
        OriginalRecordDtos.RevisionReq rev = new OriginalRecordDtos.RevisionReq();
        rev.setReason("试图修改已发放数据");
        assertThrows(RuntimeException.class, () -> recordService.startRevision(id, rev, "孙敬之"));
    }

    @Test
    void unconfirmedRecordCannotIssue() {
        Long id = createOrder("WT-R4");
        recordService.createDraft(id, "赵砚青");
        recordService.saveDraft(id, draftReq(), "赵砚青");
        // 草稿未提交/确认，结构化委托的普通推进无法跨过核验进入出证
        assertThrows(RuntimeException.class, () -> orderService.advance(id));
    }

    @Test
    void legacyBeforeVerificationCanMigrateButVerifiedLegacyIsReadonly() {
        // 未核验的旧文本：可迁移为第一版
        Long openId = createOrder("WT-R5");
        var legacy = new com.metrology.lab.dto.CalibrationOrderDtos.ResultReq();
        legacy.setMeasuredData("10V|+10.0V|+10.1V|+0.1V");
        legacy.setConclusion("PASS");
        orderService.saveResult(openId, legacy);
        recordService.migrateLegacy(openId, "赵砚青");
        Views.RecordBundleView migrated = recordService.bundle(openId, "赵砚青");
        assertTrue(migrated.structured());
        assertTrue(migrated.versions().get(0).legacy());
        assertEquals(1, migrated.versions().get(0).items().size());

        // 已核验的旧文本：只读，不能迁移
        Long oldId = createOrder("WT-R6");
        var old = new com.metrology.lab.dto.CalibrationOrderDtos.ResultReq();
        old.setMeasuredData("1V|+1|+1|+0");
        old.setConclusion("PASS");
        old.setCertNo("CERT-OLD");
        orderService.saveResult(oldId, old);
        orderService.advance(oldId); // VERIFYING（旧流程，服务端把旧文本标记为只读）
        assertThrows(RuntimeException.class, () -> recordService.migrateLegacy(oldId, "赵砚青"));
        assertEquals("VERIFYING", orderService.detail(oldId).status());
        assertEquals("LEGACY_READONLY", orderService.detail(oldId).records().mode());
        // 只读旧记录不能再退回改结论
        assertThrows(RuntimeException.class, () -> orderService.rollback(oldId));
    }
}

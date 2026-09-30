package com.metrology.lab.service;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metrology.lab.common.BizException;
import com.metrology.lab.domain.CalibrationOrder;
import com.metrology.lab.domain.Instrument;
import com.metrology.lab.domain.MetroDict;
import com.metrology.lab.domain.OriginalRecordEvent;
import com.metrology.lab.domain.OriginalRecordItem;
import com.metrology.lab.domain.OriginalRecordVersion;
import com.metrology.lab.dto.OriginalRecordDtos;
import com.metrology.lab.dto.Views;
import com.metrology.lab.repository.CalibrationOrderRepository;
import com.metrology.lab.repository.InstrumentRepository;
import com.metrology.lab.repository.OriginalRecordEventRepository;
import com.metrology.lab.repository.OriginalRecordItemRepository;
import com.metrology.lab.repository.OriginalRecordVersionRepository;

/**
 * 原始记录版本、角色分离、旧文本兼容和证书固定引用的服务端判定入口。
 *
 * <p>所有方法先锁委托行，再校验当前状态、操作者角色、指派关系和数据完整性；
 * 前端的批量录入/复制只是输入便利，不能决定版本是否提交、确认或被证书引用。
 */
@Service
public class OriginalRecordService {

    /** 演示环境的操作者目录；真实环境应由认证/网关给出用户、岗位与授权。 */
    private static final Map<String, String> STAFF_ROLE = Map.of(
            "赵砚青", "VERIFIER",
            "陈听澜", "VERIFIER",
            "林砚秋", "VERIFIER",
            "周慕言", "VERIFIER",
            "孙敬之", "CHECKER",
            "王核验", "CHECKER",
            "调度管理员", "COORDINATOR");

    private final CalibrationOrderRepository orderRepository;
    private final InstrumentRepository instrumentRepository;
    private final OriginalRecordVersionRepository versionRepository;
    private final OriginalRecordItemRepository itemRepository;
    private final OriginalRecordEventRepository eventRepository;

    public OriginalRecordService(CalibrationOrderRepository orderRepository,
                                 InstrumentRepository instrumentRepository,
                                 OriginalRecordVersionRepository versionRepository,
                                 OriginalRecordItemRepository itemRepository,
                                 OriginalRecordEventRepository eventRepository) {
        this.orderRepository = orderRepository;
        this.instrumentRepository = instrumentRepository;
        this.versionRepository = versionRepository;
        this.itemRepository = itemRepository;
        this.eventRepository = eventRepository;
    }

    // ============================ 查询与权限视图 ============================

    @Transactional(readOnly = true)
    public Views.RecordBundleView bundle(Long orderId, String operatorName) {
        CalibrationOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BizException("委托单不存在：id=" + orderId));
        return bundle(order, parseOperator(operatorName));
    }

    @Transactional(readOnly = true)
    public Views.RecordBundleView bundle(CalibrationOrder order, Operator operator) {
        List<OriginalRecordVersion> versions = versionRepository.findByOrderIdOrderByVersionNoAsc(order.getId());
        Map<Long, List<OriginalRecordItem>> itemMap = loadItems(versions);
        Map<Long, OriginalRecordVersion> byId = versions.stream()
                .collect(Collectors.toMap(OriginalRecordVersion::getId, v -> v));

        List<Views.RecordVersionView> versionViews = new ArrayList<>();
        for (int i = 0; i < versions.size(); i++) {
            OriginalRecordVersion version = versions.get(i);
            OriginalRecordVersion previous = i == 0 ? null : versions.get(i - 1);
            List<Views.RecordDiffView> changes = previous == null ? List.of()
                    : diff(previous, itemMap.getOrDefault(previous.getId(), List.of()),
                            version, itemMap.getOrDefault(version.getId(), List.of()));
            versionViews.add(toVersionView(order, version, itemMap.getOrDefault(version.getId(), List.of()),
                    changes, false));
        }

        List<Views.RecordEventView> events = eventRepository
                .findByOrderIdOrderByCreatedAtAscIdAsc(order.getId())
                .stream()
                .map(e -> toEventView(e, byId))
                .toList();

        OriginalRecordVersion draft = editableVersion(versions);
        OriginalRecordVersion current = order.getCurrentRecordVersionId() == null ? null
                : byId.get(order.getCurrentRecordVersionId());
        OriginalRecordVersion certificateVersion = order.getCertRecordVersionId() == null ? null
                : byId.get(order.getCertRecordVersionId());
        List<Views.RecordItemView> certificateItems;
        if (certificateVersion != null) {
            certificateItems = itemMap.getOrDefault(certificateVersion.getId(), List.of()).stream()
                    .map(this::toItemView).toList();
        } else if (structured) {
            certificateItems = List.of();
        } else {
            certificateItems = legacyItems(order);
        }

        boolean structured = MetroDict.MODE_STRUCTURED.equals(order.getRecordMode());
        boolean hasFixedVersion = order.getCertRecordVersionId() != null;
        boolean legacyText = MetroDict.MODE_LEGACY_TEXT.equals(order.getRecordMode());
        boolean legacyReadonly = MetroDict.MODE_LEGACY_READONLY.equals(order.getRecordMode());
        boolean beforeVerification = "RECEIVED".equals(order.getStatus()) || "CALIBRATING".equals(order.getStatus());
        boolean canMigrateLegacy = (legacyText || (!structured && !legacyReadonly && hasText(order)))
                && beforeVerification && !MetroDict.STATUS_VOIDED.equals(order.getStatus());
        boolean canAssign = !"ISSUED".equals(order.getStatus()) && !MetroDict.STATUS_VOIDED.equals(order.getStatus())
                && operator.role().equals("COORDINATOR");
        boolean canSaveDraft = structured && draft != null
                && isAssigned(operator, order.getVerifier(), "VERIFIER")
                && ("CALIBRATING".equals(order.getStatus()) || "RECEIVED".equals(order.getStatus()));
        boolean canSubmit = canSaveDraft && "CALIBRATING".equals(order.getStatus());
        boolean canReview = structured && "VERIFYING".equals(order.getStatus())
                && isAssigned(operator, order.getChecker(), "CHECKER");
        boolean canWithdraw = structured && "VERIFYING".equals(order.getStatus())
                && (isAssigned(operator, order.getVerifier(), "VERIFIER") || "COORDINATOR".equals(operator.role()));
        boolean canStartRevision = structured && "CERTIFYING".equals(order.getStatus())
                && (isAssigned(operator, order.getChecker(), "CHECKER") || "COORDINATOR".equals(operator.role()));

        String certSource = hasFixedVersion ? "STRUCTURED_VERSION"
                : legacyReadonly || "ISSUED".equals(order.getStatus()) ? "LEGACY_TEXT_FROZEN"
                : "LEGACY_TEXT";
        String certConclusion = hasFixedVersion && certificateVersion != null
                ? certificateVersion.getConclusion()
                : (hasFixedVersion ? null : order.getConclusion());
        String hint = actionHint(order, draft, canSaveDraft, canSubmit, canReview, canWithdraw, canStartRevision,
                canMigrateLegacy, operator);

        return new Views.RecordBundleView(
                order.getRecordMode(),
                MetroDict.recordModeName(order.getRecordMode()),
                structured,
                legacyText,
                legacyReadonly,
                order.getMeasuredData(),
                canMigrateLegacy,
                draft == null ? null : draft.getId(),
                current == null ? null : current.getId(),
                current == null ? null : current.getVersionNo(),
                certificateVersion == null ? null : certificateVersion.getId(),
                certificateVersion == null ? null : certificateVersion.getVersionNo(),
                certSource,
                certSourceName(certSource),
                certConclusion,
                MetroDict.conclusionName(certConclusion),
                certificateItems,
                versionViews,
                events,
                operatorView(operator),
                canSaveDraft,
                canSubmit,
                canReview,
                canReview,
                canWithdraw,
                canStartRevision,
                canAssign,
                canMigrateLegacy,
                hint);
    }

    @Transactional(readOnly = true)
    public Views.OperatorView currentOperator(String name) {
        return operatorView(parseOperator(name));
    }

    /** 供已经处在事务中的其他服务使用，避免同类自调用绕开事务代理。 */
    public String operatorName(String name) {
        return parseOperator(name).name();
    }

    // ============================ 草稿 / 提交 ============================

    @Transactional
    public Views.RecordBundleView saveDraft(Long orderId, OriginalRecordDtos.DraftSaveReq req, String actorName) {
        Operator actor = requireActor(actorName);
        CalibrationOrder order = lock(orderId);
        ensureStructured(order);
        OriginalRecordVersion draft = requireEditable(order);
        requireAssigned(actor, order.getVerifier(), "VERIFIER");
        if (!"RECEIVED".equals(order.getStatus()) && !"CALIBRATING".equals(order.getStatus())) {
            throw new BizException("当前委托已提交复核或出证，不能再直接改数；请走退回、撤回或修订的新版本路径");
        }
        saveItems(draft, req == null ? List.of() : req.getItems(), false);
        if (req != null && req.getConclusion() != null && !req.getConclusion().isBlank()
                && !MetroDict.isValidConclusion(req.getConclusion())) {
            throw new BizException("检定结论只能是 PASS / LIMITED / FAIL");
        }
        draft.setConclusion(req == null || req.getConclusion() == null || req.getConclusion().isBlank()
                ? null : req.getConclusion().trim());
        draft.setRecordRemark(trimToNull(req == null ? null : req.getRecordRemark(), 200));
        draft.setUpdatedAt(LocalDateTime.now());
        versionRepository.save(draft);
        touch(order);
        event(order, draft, "DRAFT_SAVED", actor, "保存结构化原始记录草稿", null);
        return bundle(order, actor);
    }

    @Transactional
    public Views.RecordBundleView submit(Long orderId, OriginalRecordDtos.DraftSaveReq req, String actorName) {
        Operator actor = requireActor(actorName);
        CalibrationOrder order = lock(orderId);
        ensureStructured(order);
        OriginalRecordVersion draft = requireEditable(order);
        requireAssigned(actor, order.getVerifier(), "VERIFIER");
        if (!"CALIBRATING".equals(order.getStatus())) {
            throw new BizException("只有检定中的草稿可以提交复核；请先推进到检定中");
        }
        if (req != null) {
            saveItems(draft, req.getItems(), true);
            draft.setConclusion(trimToNull(req.getConclusion(), 16));
            draft.setRecordRemark(trimToNull(req.getRecordRemark(), 200));
        }
        List<OriginalRecordItem> items = itemRepository.findByVersionIdOrderByItemNoAscIdAsc(draft.getId());
        // 提交瞬间把当前责任人员固化到版本；之后人员调整不会回写已锁定版本
        draft.setVerifierName(placeholderToNull(order.getVerifier()));
        draft.setCheckerName(placeholderToNull(order.getChecker()));
        validateSubmitted(draft, items);

        LocalDateTime now = LocalDateTime.now();
        draft.setStatus(MetroDict.RECORD_SUBMITTED);
        draft.setSubmittedAt(now);
        draft.setLockedAt(now);
        draft.setUpdatedAt(now);
        versionRepository.save(draft);
        order.setStatus("VERIFYING");
        order.setUpdatedAt(now);
        orderRepository.save(order);
        event(order, draft, "SUBMITTED", actor,
                "提交核验，第 " + draft.getVersionNo() + " 版已锁定；核验员只能通过或退回", null);
        return bundle(order, actor);
    }

    // ============================ 核验通过 / 退回 ============================

    @Transactional
    public Views.RecordBundleView approve(Long orderId, String actorName) {
        Operator actor = requireActor(actorName);
        CalibrationOrder order = lock(orderId);
        ensureStructured(order);
        if (!"VERIFYING".equals(order.getStatus())) {
            throw new BizException("只有待核验版本可以确认通过");
        }
        requireAssigned(actor, order.getChecker(), "CHECKER");
        OriginalRecordVersion version = submittedVersion(order);
        if (actor.name().equals(version.getVerifierName())) {
            throw new BizException("同一版本的提交人和核验确认人不能是同一人");
        }
        LocalDateTime now = LocalDateTime.now();
        supersedeCurrent(order, now);
        version.setStatus(MetroDict.RECORD_CONFIRMED);
        version.setReviewedAt(now);
        version.setUpdatedAt(now);
        versionRepository.save(version);

        order.setCurrentRecordVersionId(version.getId());
        order.setConclusion(version.getConclusion());
        order.setStatus("CERTIFYING");
        order.setCertRecordVersionId(version.getId());
        if (order.getCertValidUntil() == null) {
            order.setCertValidUntil(defaultValidUntil(order));
        }
        order.setUpdatedAt(now);
        orderRepository.save(order);
        event(order, version, "APPROVED", actor,
                "核验通过；出证预览及最终发放固定引用第 " + version.getVersionNo() + " 版", null);
        return bundle(order, actor);
    }

    @Transactional
    public Views.RecordBundleView returnVersion(Long orderId, OriginalRecordDtos.ReviewReq req, String actorName) {
        Operator actor = requireActor(actorName);
        CalibrationOrder order = lock(orderId);
        ensureStructured(order);
        if (!"VERIFYING".equals(order.getStatus())) {
            throw new BizException("只有待核验版本可以退回");
        }
        requireAssigned(actor, order.getChecker(), "CHECKER");
        OriginalRecordVersion submitted = submittedVersion(order);
        if (actor.name().equals(submitted.getVerifierName())) {
            throw new BizException("检定员和核验员不能由同一人完成；不能自行退回后规避确认");
        }
        String reason = required(req == null ? null : req.getReason(), "退回原因");
        LocalDateTime now = LocalDateTime.now();
        submitted.setStatus(MetroDict.RECORD_RETURNED);
        submitted.setReviewedAt(now);
        submitted.setRejectReason(reason);
        submitted.setUpdatedAt(now);
        versionRepository.save(submitted);

        OriginalRecordVersion draft = copyVersion(order, submitted, "COPY_REJECTED", MetroDict.RECORD_DRAFT, now);
        order.setStatus("CALIBRATING");
        order.setUpdatedAt(now);
        orderRepository.save(order);
        event(order, submitted, "RETURNED", actor, reason, null);
        event(order, draft, "DRAFT_SAVED", actor,
                "退回后自动复制生成第 " + draft.getVersionNo() + " 版草稿，原退回版本继续锁定可查", submitted.getId());
        return bundle(order, actor);
    }

    /** 误提交的可恢复路径：提交人或调度管理员可撤回，旧版本锁定，复制出新草稿。 */
    @Transactional
    public Views.RecordBundleView withdraw(Long orderId, String actorName, String reason) {
        Operator actor = requireActor(actorName);
        CalibrationOrder order = lock(orderId);
        ensureStructured(order);
        if (!"VERIFYING".equals(order.getStatus())) {
            throw new BizException("只有待核验版本可以撤回");
        }
        if (!isAssigned(actor, order.getVerifier(), "VERIFIER") && !"COORDINATOR".equals(actor.role())) {
            throw new BizException("只有被指派检定员或调度管理员可以处理误提交");
        }
        OriginalRecordVersion submitted = submittedVersion(order);
        LocalDateTime now = LocalDateTime.now();
        submitted.setStatus(MetroDict.RECORD_WITHDRAWN);
        submitted.setRejectReason(trimToNull(reason, 300));
        submitted.setReviewedAt(now);
        submitted.setUpdatedAt(now);
        versionRepository.save(submitted);

        OriginalRecordVersion draft = copyVersion(order, submitted, "COPY_WITHDRAWN", MetroDict.RECORD_DRAFT, now);
        order.setStatus("CALIBRATING");
        order.setUpdatedAt(now);
        orderRepository.save(order);
        event(order, submitted, "WITHDRAWN", actor,
                trimToNull(reason, 300) == null ? "误提交撤回，旧版本保留" : reason, null);
        event(order, draft, "DRAFT_SAVED", actor,
                "撤回后复制生成第 " + draft.getVersionNo() + " 版草稿", submitted.getId());
        return bundle(order, actor);
    }

    /** 出证前修订：已确认版本不删，复制新草稿；重新确认后才允许新证书数据。 */
    @Transactional
    public Views.RecordBundleView startRevision(Long orderId, OriginalRecordDtos.RevisionReq req, String actorName) {
        Operator actor = requireActor(actorName);
        CalibrationOrder order = lock(orderId);
        ensureStructured(order);
        if (!"CERTIFYING".equals(order.getStatus())) {
            throw new BizException("只有出证前的已确认版本可以发起修订；已发放证书必须另开更正委托");
        }
        if (!isAssigned(actor, order.getChecker(), "CHECKER") && !"COORDINATOR".equals(actor.role())) {
            throw new BizException("只有被指派核验员或调度管理员可以发起出证前修订");
        }
        OriginalRecordVersion confirmed = order.getCurrentRecordVersionId() == null ? null
                : versionRepository.findById(order.getCurrentRecordVersionId()).orElse(null);
        if (confirmed == null || !MetroDict.RECORD_CONFIRMED.equals(confirmed.getStatus())) {
            throw new BizException("未找到当前已确认版本，不能发起修订");
        }
        String reason = required(req == null ? null : req.getReason(), "修订原因");
        LocalDateTime now = LocalDateTime.now();
        confirmed.setStatus(MetroDict.RECORD_SUPERSEDED);
        confirmed.setUpdatedAt(now);
        versionRepository.save(confirmed);

        OriginalRecordVersion draft = copyVersion(order, confirmed, "COPY_REVISION", MetroDict.RECORD_DRAFT, now);
        order.setCurrentRecordVersionId(null);
        // 尚未发放，清掉待发证书预览引用；避免修订草稿静默改变一个看似仍可出证的版本。
        order.setCertRecordVersionId(null);
        order.setStatus("CALIBRATING");
        order.setUpdatedAt(now);
        orderRepository.save(order);
        event(order, confirmed, "REVISION_STARTED", actor, reason, null);
        event(order, draft, "DRAFT_SAVED", actor,
                "修订复制生成第 " + draft.getVersionNo() + " 版草稿；重新核验通过后才可继续出证", confirmed.getId());
        return bundle(order, actor);
    }

    // ============================ 人员调整与旧文本迁移 ============================

    @Transactional
    public Views.RecordBundleView assign(Long orderId, OriginalRecordDtos.AssignReq req, String actorName) {
        Operator actor = requireActor(actorName);
        if (!"COORDINATOR".equals(actor.role())) {
            throw new BizException("人员调整只能由调度管理员执行；检定员/核验员不得自行代指派");
        }
        CalibrationOrder order = lock(orderId);
        if ("ISSUED".equals(order.getStatus()) || MetroDict.STATUS_VOIDED.equals(order.getStatus())) {
            throw new BizException("已发放或已作废委托的责任人员只作历史留痕，不能调整");
        }
        if (req == null) {
            throw new BizException("人员调整内容不能为空");
        }
        String reason = required(req.getReason(), "人员调整原因");
        String verifier = trimToNull(req.getVerifier(), 32);
        String checker = trimToNull(req.getChecker(), 32);
        if (verifier != null && !"VERIFIER".equals(roleOf(verifier))) {
            throw new BizException(verifier + " 不是检定员，不能指派为检定员");
        }
        if (checker != null && !"CHECKER".equals(roleOf(checker))) {
            throw new BizException(checker + " 不是核验员，不能指派为核验员");
        }
        if (verifier != null && verifier.equals(checker)) {
            throw new BizException("同一版本的检定员和核验员不能为同一人");
        }
        LocalDateTime now = LocalDateTime.now();
        Long eventVersionId = order.getCurrentRecordVersionId();
        OriginalRecordVersion editable = MetroDict.MODE_STRUCTURED.equals(order.getRecordMode())
                ? editableVersion(versionRepository.findByOrderIdOrderByVersionNoAsc(order.getId())) : null;
        if (verifier != null) {
            order.setVerifier(verifier);
            if (editable != null) {
                editable.setVerifierName(verifier);
                editable.setUpdatedAt(now);
                versionRepository.save(editable);
                eventVersionId = editable.getId();
            }
        }
        if (checker != null) {
            order.setChecker(checker);
            if (editable != null) {
                editable.setCheckerName(checker);
                editable.setUpdatedAt(now);
                versionRepository.save(editable);
                eventVersionId = editable.getId();
            }
        }
        order.setUpdatedAt(now);
        orderRepository.save(order);
        if (eventVersionId == null) {
            eventVersionId = versionRepository.findFirstByOrderIdOrderByVersionNoDesc(order.getId())
                    .map(OriginalRecordVersion::getId).orElse(null);
        }
        if (eventVersionId != null) {
            OriginalRecordEvent event = new OriginalRecordEvent();
            event.setOrderId(order.getId());
            event.setVersionId(eventVersionId);
            event.setEventType("ASSIGNED");
            event.setActorName(actor.name());
            event.setActorRole(actor.role());
            event.setDetail("人员调整：" + reason);
            event.setCreatedAt(now);
            eventRepository.save(event);
        }
        return bundle(order, actor);
    }

    @Transactional
    public Views.RecordBundleView migrateLegacy(Long orderId, String actorName) {
        Operator actor = requireActor(actorName);
        CalibrationOrder order = lock(orderId);
        if (MetroDict.MODE_STRUCTURED.equals(order.getRecordMode())) {
            throw new BizException("该委托已是结构化记录，无需转换");
        }
        if (MetroDict.MODE_LEGACY_READONLY.equals(order.getRecordMode())
                || "VERIFYING".equals(order.getStatus())
                || "CERTIFYING".equals(order.getStatus())
                || "ISSUED".equals(order.getStatus())
                || MetroDict.STATUS_VOIDED.equals(order.getStatus())) {
            throw new BizException("已进入核验、出证、发放或作废的旧委托只能只读保留，不能改变原结论");
        }
        if (!isAssigned(actor, order.getVerifier(), "VERIFIER") && !"COORDINATOR".equals(actor.role())) {
            throw new BizException("旧文本转换为第一版必须由被指派检定员或调度管理员确认");
        }
        if (!hasText(order)) {
            throw new BizException("旧文本没有可转换的数据项；请直接新建结构化空白草稿");
        }
        LocalDateTime now = LocalDateTime.now();
        OriginalRecordVersion draft = new OriginalRecordVersion();
        draft.setOrderId(order.getId());
        draft.setVersionNo(nextVersionNo(order.getId()));
        draft.setStatus(MetroDict.RECORD_DRAFT);
        draft.setSource("MIGRATED");
        draft.setLegacySourceText(order.getMeasuredData());
        draft.setVerifierName(placeholderToNull(order.getVerifier()));
        draft.setCheckerName(placeholderToNull(order.getChecker()));
        draft.setConclusion(order.getConclusion());
        draft.setCreatedAt(now);
        draft.setUpdatedAt(now);
        versionRepository.save(draft);
        saveItems(draft, parseLegacyItems(order.getMeasuredData()), false);
        order.setRecordMode(MetroDict.MODE_STRUCTURED);
        order.setCurrentRecordVersionId(null);
        order.setCertRecordVersionId(null);
        order.setUpdatedAt(now);
        orderRepository.save(order);
        event(order, draft, "MIGRATED", actor,
                "用户确认后由旧文本转换为第一版结构化草稿；原 measured_data 原文继续保留为历史记录", null);
        return bundle(order, actor);
    }

    @Transactional
    public Views.RecordBundleView createDraft(Long orderId, String actorName) {
        Operator actor = requireActor(actorName);
        CalibrationOrder order = lock(orderId);
        if (MetroDict.MODE_STRUCTURED.equals(order.getRecordMode())) {
            throw new BizException("结构化记录已存在，不能重复建立草稿");
        }
        if (MetroDict.MODE_LEGACY_READONLY.equals(order.getRecordMode())
                || !"RECEIVED".equals(order.getStatus()) && !"CALIBRATING".equals(order.getStatus())) {
            throw new BizException("当前状态不能新建结构化草稿；已核验或已发放旧记录只能只读保留");
        }
        if (!isAssigned(actor, order.getVerifier(), "VERIFIER") && !"COORDINATOR".equals(actor.role())) {
            throw new BizException("只有被指派检定员或调度管理员可以建立原始记录");
        }
        LocalDateTime now = LocalDateTime.now();
        OriginalRecordVersion draft = new OriginalRecordVersion();
        draft.setOrderId(order.getId());
        draft.setVersionNo(nextVersionNo(order.getId()));
        draft.setStatus(MetroDict.RECORD_DRAFT);
        draft.setSource("NEW");
        draft.setLegacySourceText(trimToNull(order.getMeasuredData(), 500));
        draft.setVerifierName(placeholderToNull(order.getVerifier()));
        draft.setCheckerName(placeholderToNull(order.getChecker()));
        draft.setCreatedAt(now);
        draft.setUpdatedAt(now);
        versionRepository.save(draft);
        order.setRecordMode(MetroDict.MODE_STRUCTURED);
        order.setUpdatedAt(now);
        orderRepository.save(order);
        if (hasText(order)) {
            saveItems(draft, parseLegacyItems(order.getMeasuredData()), false);
            event(order, draft, "MIGRATED", actor, "建立结构化记录时带入旧文本作为可编辑草稿，原文保留", null);
        } else {
            event(order, draft, "DRAFT_SAVED", actor, "建立空白结构化原始记录草稿", null);
        }
        return bundle(order, actor);
    }

    /** 发放时再次锁定/校验证书引用；任何修订都不能改掉已经发放的数据。 */
    @Transactional
    public void freezeOnIssue(CalibrationOrder order) {
        if (!MetroDict.MODE_STRUCTURED.equals(order.getRecordMode())) {
            if (MetroDict.MODE_LEGACY_TEXT.equals(order.getRecordMode())) {
                order.setRecordMode(MetroDict.MODE_LEGACY_READONLY);
            }
            return;
        }
        Long versionId = order.getCertRecordVersionId();
        OriginalRecordVersion version = versionId == null ? null
                : versionRepository.findById(versionId).orElseThrow(() -> new BizException("证书缺少固定记录版本"));
        if (!MetroDict.RECORD_CONFIRMED.equals(version.getStatus())) {
            throw new BizException("证书只能引用核验已确认且未被替代的版本");
        }
        if (!versionId.equals(order.getCurrentRecordVersionId())) {
            throw new BizException("证书固定版本与当前确认版本不一致，请重新核验后再发放");
        }
    }

    // ============================ 内部：版本与数据项 ============================

    private CalibrationOrder lock(Long id) {
        return orderRepository.lockById(id).orElseThrow(() -> new BizException("委托单不存在：id=" + id));
    }

    private LocalDate defaultValidUntil(CalibrationOrder order) {
        Instrument instrument = order.getInstrumentId() == null ? null
                : instrumentRepository.findById(order.getInstrumentId()).orElse(null);
        int cycle = instrument == null || instrument.getCycleMonths() == null ? 12 : instrument.getCycleMonths();
        LocalDate from = order.getReceiveDate() == null ? LocalDate.now() : order.getReceiveDate();
        return from.plusMonths(cycle);
    }

    private void ensureStructured(CalibrationOrder order) {
        if (!MetroDict.MODE_STRUCTURED.equals(order.getRecordMode())) {
            throw new BizException("该委托仍是旧文本记录；尚未核验时请先由有权人员确认转换为结构化第一版");
        }
    }

    private OriginalRecordVersion requireEditable(CalibrationOrder order) {
        OriginalRecordVersion draft = editableVersion(versionRepository.findByOrderIdOrderByVersionNoAsc(order.getId()));
        if (draft == null) {
            throw new BizException("当前没有可编辑草稿；退回、撤回或修订会自动生成新版本草稿");
        }
        return draft;
    }

    private OriginalRecordVersion submittedVersion(CalibrationOrder order) {
        return versionRepository.findByOrderIdOrderByVersionNoAsc(order.getId()).stream()
                .filter(v -> MetroDict.RECORD_SUBMITTED.equals(v.getStatus()))
                .max(Comparator.comparingInt(OriginalRecordVersion::getVersionNo))
                .orElseThrow(() -> new BizException("未找到已提交且待核验的版本"));
    }

    private OriginalRecordVersion editableVersion(List<OriginalRecordVersion> versions) {
        return versions.stream()
                .filter(v -> MetroDict.isEditableRecord(v.getStatus()))
                .max(Comparator.comparingInt(OriginalRecordVersion::getVersionNo))
                .orElse(null);
    }

    private void supersedeCurrent(CalibrationOrder order, LocalDateTime now) {
        if (order.getCurrentRecordVersionId() == null) {
            return;
        }
        OriginalRecordVersion current = versionRepository.findById(order.getCurrentRecordVersionId()).orElse(null);
        if (current != null && MetroDict.RECORD_CONFIRMED.equals(current.getStatus())) {
            current.setStatus(MetroDict.RECORD_SUPERSEDED);
            current.setUpdatedAt(now);
            versionRepository.save(current);
        }
    }

    private OriginalRecordVersion copyVersion(CalibrationOrder order, OriginalRecordVersion source,
                                               String sourceType, String targetStatus, LocalDateTime now) {
        OriginalRecordVersion copy = new OriginalRecordVersion();
        copy.setOrderId(order.getId());
        copy.setVersionNo(nextVersionNo(order.getId()));
        copy.setStatus(targetStatus);
        copy.setSource(sourceType);
        copy.setSourceVersionId(source.getId());
        copy.setLegacySourceText(source.getLegacySourceText());
        copy.setVerifierName(source.getVerifierName());
        copy.setCheckerName(source.getCheckerName());
        copy.setConclusion(source.getConclusion());
        copy.setRecordRemark(source.getRecordRemark());
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        if (!MetroDict.RECORD_DRAFT.equals(targetStatus)) {
            copy.setLockedAt(now);
        }
        versionRepository.save(copy);
        List<OriginalRecordItem> sourceItems = itemRepository.findByVersionIdOrderByItemNoAscIdAsc(source.getId());
        int no = 1;
        LocalDateTime itemTime = LocalDateTime.now();
        for (OriginalRecordItem old : sourceItems) {
            OriginalRecordItem item = new OriginalRecordItem();
            item.setVersionId(copy.getId());
            item.setItemNo(no++);
            item.setCalibrationPoint(old.getCalibrationPoint());
            item.setStandardValue(old.getStandardValue());
            item.setIndicatedValue(old.getIndicatedValue());
            item.setErrorValue(old.getErrorValue());
            item.setUnit(old.getUnit());
            item.setRemark(old.getRemark());
            item.setCreatedAt(itemTime);
            itemRepository.save(item);
        }
        return copy;
    }

    private int nextVersionNo(Long orderId) {
        return versionRepository.findFirstByOrderIdOrderByVersionNoDesc(orderId)
                .map(v -> v.getVersionNo() + 1)
                .orElse(1);
    }

    private void saveItems(OriginalRecordVersion version, List<OriginalRecordDtos.RecordItemReq> reqItems,
                           boolean submitting) {
        List<OriginalRecordDtos.RecordItemReq> items = reqItems == null ? List.of() : reqItems;
        itemRepository.deleteByVersionId(version.getId());
        itemRepository.flush();
        int no = 1;
        LocalDateTime now = LocalDateTime.now();
        Set<String> rowKeys = new HashSet<>();
        for (OriginalRecordDtos.RecordItemReq req : items) {
            if (req == null) {
                continue;
            }
            OriginalRecordItem item = new OriginalRecordItem();
            item.setVersionId(version.getId());
            item.setItemNo(no);
            item.setCalibrationPoint(validateField(req.getCalibrationPoint(), "校准点", 80, submitting));
            item.setStandardValue(validateField(req.getStandardValue(), "标准值", 80, submitting));
            item.setIndicatedValue(validateField(req.getIndicatedValue(), "示值", 80, submitting));
            item.setErrorValue(validateField(req.getErrorValue(), "误差", 80, submitting));
            item.setUnit(validateField(req.getUnit(), "单位", 24, submitting));
            item.setRemark(trimToNull(req.getRemark(), 120));
            item.setCreatedAt(now);
            itemRepository.save(item);
            if (req.getRowKey() != null && !req.getRowKey().isBlank()) {
                rowKeys.add(req.getRowKey());
            }
            no++;
        }
    }

    private void validateSubmitted(OriginalRecordVersion version, List<OriginalRecordItem> items) {
        if (items.isEmpty()) {
            throw new BizException("提交复核前至少需要一条校准数据项；数据项缺失请在草稿中补齐");
        }
        for (OriginalRecordItem item : items) {
            if (isBlank(item.getCalibrationPoint()) || isBlank(item.getStandardValue())
                    || isBlank(item.getIndicatedValue()) || isBlank(item.getErrorValue())
                    || isBlank(item.getUnit())) {
                throw new BizException("第 " + item.getItemNo() + " 行存在必填项缺失，补齐后才能提交复核");
            }
        }
        if (version.getConclusion() == null || !MetroDict.isValidConclusion(version.getConclusion())) {
            throw new BizException("提交复核前必须选择检定结论：合格 / 限用 / 不合格");
        }
        if (isBlank(version.getVerifierName()) || isBlank(version.getCheckerName())) {
            throw new BizException("提交前必须指派检定员和核验员，且二者不能为同一人");
        }
        if (version.getVerifierName().equals(version.getCheckerName())) {
            throw new BizException("检定员与核验员不能由同一人完成同一版本");
        }
    }

    private String validateField(String value, String label, int max, boolean submitting) {
        String text = trimToNull(value, max);
        if (submitting && text == null) {
            throw new BizException(label + "不能为空");
        }
        return text == null ? "" : text;
    }

    private Map<Long, List<OriginalRecordItem>> loadItems(List<OriginalRecordVersion> versions) {
        if (versions.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = versions.stream().map(OriginalRecordVersion::getId).toList();
        Map<Long, List<OriginalRecordItem>> map = new HashMap<>();
        for (OriginalRecordItem item : itemRepository.findByVersionIdInOrderByItemNoAscIdAsc(ids)) {
            map.computeIfAbsent(item.getVersionId(), k -> new ArrayList<>()).add(item);
        }
        return map;
    }

    // ============================ 旧文本解析 ============================

    private List<OriginalRecordDtos.RecordItemReq> parseLegacyItems(String text) {
        List<OriginalRecordDtos.RecordItemReq> rows = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return rows;
        }
        for (String rawLine : text.split(";")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] p = line.split("\\|", -1);
            OriginalRecordDtos.RecordItemReq item = new OriginalRecordDtos.RecordItemReq();
            item.setCalibrationPoint(part(p, 0));
            item.setStandardValue(part(p, 1));
            item.setIndicatedValue(part(p, 2));
            item.setErrorValue(part(p, 3));
            item.setUnit(extractUnit(item.getErrorValue(), item.getCalibrationPoint(), item.getStandardValue(),
                    item.getIndicatedValue()));
            item.setRemark(p.length < 4 ? "旧文本列数不足，已按历史原文拆分，请核对" : "由旧文本转换，请核对单位");
            rows.add(item);
        }
        return rows;
    }

    private String part(String[] parts, int index) {
        return index < parts.length ? parts[index].trim() : "";
    }

    private String extractUnit(String... values) {
        for (String value : values) {
            String unit = trailingUnit(value);
            if (unit != null) {
                return unit;
            }
        }
        return "旧文本未分列";
    }

    private String trailingUnit(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        int start = -1;
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (isUnitChar(c)) {
                start = i;
            } else if (start >= 0) {
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        String unit = text.substring(start).trim();
        return unit.length() <= 12 ? unit : null;
    }

    private boolean isUnitChar(char c) {
        if (c >= '一' && c <= '龥') {
            return true;
        }
        return "°μΩ·%/".indexOf(c) >= 0 || Character.isLetter(c);
    }

    private List<Views.RecordItemView> legacyItems(CalibrationOrder order) {
        if (!hasText(order)) {
            return List.of();
        }
        List<Views.RecordItemView> rows = new ArrayList<>();
        int no = 1;
        for (OriginalRecordDtos.RecordItemReq req : parseLegacyItems(order.getMeasuredData())) {
            OriginalRecordItem item = new OriginalRecordItem();
            item.setItemNo(no++);
            item.setCalibrationPoint(blank(req.getCalibrationPoint()));
            item.setStandardValue(blank(req.getStandardValue()));
            item.setIndicatedValue(blank(req.getIndicatedValue()));
            item.setErrorValue(blank(req.getErrorValue()));
            item.setUnit(blank(req.getUnit()));
            item.setRemark(req.getRemark());
            rows.add(toItemView(item));
        }
        return rows;
    }

    private String blank(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(CalibrationOrder order) {
        return order.getMeasuredData() != null && !order.getMeasuredData().isBlank();
    }

    // ============================ 差异比较 ============================

    private List<Views.RecordDiffView> diff(OriginalRecordVersion oldVersion, List<OriginalRecordItem> oldItems,
                                            OriginalRecordVersion newVersion, List<OriginalRecordItem> newItems) {
        Map<String, Deque<OriginalRecordItem>> oldGroups = groupRows(oldItems);
        Map<String, Deque<OriginalRecordItem>> newGroups = groupRows(newItems);
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(oldGroups.keySet());
        keys.addAll(newGroups.keySet());

        List<Views.RecordDiffView> result = new ArrayList<>();
        int newNo = 1;
        for (String key : keys) {
            Deque<OriginalRecordItem> olds = oldGroups.getOrDefault(key, new ArrayDeque<>());
            Deque<OriginalRecordItem> news = newGroups.getOrDefault(key, new ArrayDeque<>());
            int count = Math.max(olds.size(), news.size());
            for (int i = 0; i < count; i++) {
                OriginalRecordItem before = olds.pollFirst();
                OriginalRecordItem after = news.pollFirst();
                result.add(diffRow(before, after, after == null ? null : newNo++));
            }
        }
        return result;
    }

    private Map<String, Deque<OriginalRecordItem>> groupRows(List<OriginalRecordItem> items) {
        Map<String, Deque<OriginalRecordItem>> groups = new LinkedHashMap<>();
        for (OriginalRecordItem item : items) {
            groups.computeIfAbsent(rowKey(item), k -> new ArrayDeque<>()).add(item);
        }
        return groups;
    }

    private String rowKey(OriginalRecordItem item) {
        return String.join("", safe(item.getCalibrationPoint()), safe(item.getStandardValue()),
                safe(item.getIndicatedValue()));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private Views.RecordDiffView diffRow(OriginalRecordItem before, OriginalRecordItem item, Integer newNo) {
        String type;
        if (before == null) {
            type = "ADDED";
        } else if (item == null) {
            type = "REMOVED";
        } else {
            type = sameFields(before, item) ? "SAME" : "MODIFIED";
        }
        List<Views.RecordFieldDiffView> fields = before == null || item == null
                ? List.of()
                : List.of(
                        field("calibrationPoint", "校准点", before.getCalibrationPoint(), item.getCalibrationPoint()),
                        field("standardValue", "标准值", before.getStandardValue(), item.getStandardValue()),
                        field("indicatedValue", "示值", before.getIndicatedValue(), item.getIndicatedValue()),
                        field("errorValue", "误差", before.getErrorValue(), item.getErrorValue()),
                        field("unit", "单位", before.getUnit(), item.getUnit()),
                        field("remark", "备注", before.getRemark(), item.getRemark()));
        return new Views.RecordDiffView(
                item != null ? item.getItemNo() : before.getItemNo(),
                type,
                diffTypeName(type),
                before == null ? null : toItemView(before),
                item == null ? null : toItemView(item),
                fields);
    }

    private boolean sameFields(OriginalRecordItem a, OriginalRecordItem b) {
        return Objects.equals(a.getCalibrationPoint(), b.getCalibrationPoint())
                && Objects.equals(a.getStandardValue(), b.getStandardValue())
                && Objects.equals(a.getIndicatedValue(), b.getIndicatedValue())
                && Objects.equals(a.getErrorValue(), b.getErrorValue())
                && Objects.equals(a.getUnit(), b.getUnit())
                && Objects.equals(a.getRemark(), b.getRemark());
    }

    private Views.RecordFieldDiffView field(String key, String name, String before, String after) {
        return new Views.RecordFieldDiffView(key, name, before, after, !Objects.equals(before, after));
    }

    private String diffTypeName(String type) {
        return switch (type) {
            case "ADDED" -> "新增";
            case "REMOVED" -> "删除";
            case "MODIFIED" -> "修改";
            default -> "相同";
        };
    }

    // ============================ 视图转换 ============================

    private Views.RecordVersionView toVersionView(CalibrationOrder order, OriginalRecordVersion version,
                                                   List<OriginalRecordItem> items,
                                                   List<Views.RecordDiffView> changes,
                                                   boolean includeItems) {
        boolean locked = !MetroDict.RECORD_DRAFT.equals(version.getStatus());
        boolean current = order.getCurrentRecordVersionId() != null
                && order.getCurrentRecordVersionId().equals(version.getId());
        boolean certVersion = order.getCertRecordVersionId() != null
                && order.getCertRecordVersionId().equals(version.getId());
        return new Views.RecordVersionView(
                version.getId(),
                version.getVersionNo(),
                version.getStatus(),
                MetroDict.recordStatusName(version.getStatus()),
                version.getSource(),
                MetroDict.recordSourceName(version.getSource()),
                version.getSourceVersionId(),
                version.getSourceVersionId() == null ? null
                        : versionRepository.findById(version.getSourceVersionId())
                                .map(OriginalRecordVersion::getVersionNo).orElse(null),
                version.getLegacySourceText(),
                version.getLegacySourceText() != null && !version.getLegacySourceText().isBlank(),
                version.getVerifierName(),
                version.getCheckerName(),
                version.getConclusion(),
                MetroDict.conclusionName(version.getConclusion()),
                version.getRecordRemark(),
                version.getSubmittedAt(),
                version.getReviewedAt(),
                version.getRejectReason(),
                version.getCreatedAt(),
                version.getUpdatedAt(),
                version.getLockedAt(),
                !locked,
                locked,
                current,
                certVersion,
                items.size(),
                includeItems ? items.stream().map(this::toItemView).toList()
                        : items.stream().map(this::toItemView).toList(),
                changes);
    }

    private Views.RecordItemView toItemView(OriginalRecordItem item) {
        return new Views.RecordItemView(
                item.getId(),
                item.getItemNo(),
                "item-" + item.getId(),
                item.getCalibrationPoint(),
                item.getStandardValue(),
                item.getIndicatedValue(),
                item.getErrorValue(),
                item.getUnit(),
                item.getRemark());
    }

    private Views.RecordEventView toEventView(OriginalRecordEvent event, Map<Long, OriginalRecordVersion> versions) {
        OriginalRecordVersion version = versions.get(event.getVersionId());
        OriginalRecordVersion from = event.getFromVersionId() == null ? null
                : versions.get(event.getFromVersionId());
        return new Views.RecordEventView(
                event.getId(),
                event.getVersionId(),
                version == null ? null : version.getVersionNo(),
                event.getEventType(),
                MetroDict.recordEventName(event.getEventType()),
                event.getActorName(),
                event.getActorRole(),
                MetroDict.actorRoleName(event.getActorRole()),
                event.getDetail(),
                event.getFromVersionId(),
                from == null ? null : from.getVersionNo(),
                event.getCreatedAt());
    }

    private void event(CalibrationOrder order, OriginalRecordVersion version, String type, Operator actor,
                       String detail, Long fromVersionId) {
        OriginalRecordEvent event = new OriginalRecordEvent();
        event.setOrderId(order.getId());
        event.setVersionId(version.getId());
        event.setEventType(type);
        event.setActorName(actor.name());
        event.setActorRole(actor.role());
        event.setDetail(detail);
        event.setFromVersionId(fromVersionId);
        event.setCreatedAt(LocalDateTime.now());
        eventRepository.save(event);
    }

    private void touch(CalibrationOrder order) {
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);
    }

    // ============================ 操作者与权限 ============================

    private Operator parseOperator(String rawName) {
        String name = trimToNull(rawName, 32);
        if (name == null) {
            return new Operator(null, "ANONYMOUS");
        }
        return new Operator(name, roleOf(name));
    }

    private Operator requireActor(String rawName) {
        Operator operator = parseOperator(rawName);
        if (operator.name() == null) {
            throw new BizException("缺少操作者信息；请先在页面顶部选择当前操作人员");
        }
        return operator;
    }

    private String roleOf(String name) {
        return STAFF_ROLE.getOrDefault(name, "UNKNOWN");
    }

    private boolean isAssigned(Operator operator, String assignedName, String role) {
        return operator != null
                && role.equals(operator.role())
                && operator.name() != null
                && operator.name().equals(trimToNull(assignedName, 32));
    }

    private void requireAssigned(Operator operator, String assignedName, String role) {
        if (!isAssigned(operator, assignedName, role)) {
            String roleName = MetroDict.actorRoleName(role);
            String assigned = trimToNull(assignedName, 32);
            if (assigned == null) {
                throw new BizException("尚未指派" + roleName + "，请调度管理员先完成人员指派");
            }
            throw new BizException("当前操作者不是本笔委托的被指派" + roleName + "，不能代操作；应使用：" + assigned);
        }
    }

    private Views.OperatorView operatorView(Operator operator) {
        boolean authenticated = operator.name() != null && !"UNKNOWN".equals(operator.role())
                && !"ANONYMOUS".equals(operator.role());
        return new Views.OperatorView(operator.name(), operator.role(),
                MetroDict.actorRoleName(operator.role()), authenticated);
    }

    private String actionHint(CalibrationOrder order, OriginalRecordVersion draft,
                              boolean canSaveDraft, boolean canSubmit, boolean canReview,
                              boolean canWithdraw, boolean canStartRevision, boolean canMigrate,
                              Operator operator) {
        if (MetroDict.STATUS_VOIDED.equals(order.getStatus())) {
            return "委托已作废，所有原始记录版本只读留档";
        }
        if ("ISSUED".equals(order.getStatus())) {
            return "证书已发放，页面数据固定来自发放时确认版本；后续修订必须另开更正委托";
        }
        if (canMigrate) {
            return "该委托仍为旧文本记录；确认后可转换为第一版结构化草稿，原文本继续标记保留";
        }
        if (canReview) {
            return "核验员只能确认通过，或写明原因退回；不能直接替检定员改数";
        }
        if (canSubmit) {
            return "检定员可批量编辑草稿；提交后本版本立即锁定";
        }
        if (canSaveDraft) {
            return "草稿阶段可批量增删、复制和粘贴；缺项可先保存，但提交前必须补齐";
        }
        if (canWithdraw) {
            return "发现误提交可撤回：旧版本锁定留痕，系统复制新草稿供修正";
        }
        if (canStartRevision) {
            return "出证前确需修订时，必须写明原因并生成新版本；已确认旧版保留为已替代";
        }
        if (MetroDict.MODE_LEGACY_READONLY.equals(order.getRecordMode())) {
            return "这是升级前已核验/发放的旧文本，按历史记录只读保留";
        }
        if (operator.name() == null) {
            return "请选择当前操作人员；服务端会最终校验角色和指派关系";
        }
        return "当前操作人员不是本环节被指派人员，只能查看，不能代操作";
    }

    private String certSourceName(String source) {
        return switch (source) {
            case "STRUCTURED_VERSION" -> "已确认结构化版本";
            case "LEGACY_TEXT_FROZEN" -> "旧文本（已冻结）";
            default -> "旧文本历史记录";
        };
    }

    private String placeholderToNull(String value) {
        String text = trimToNull(value, 32);
        return "待分配".equals(text) ? null : text;
    }

    private String required(String value, String label) {
        String text = trimToNull(value, 300);
        if (text == null) {
            throw new BizException(label + "不能为空");
        }
        return text;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String trimToNull(String value, int max) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > max ? text.substring(0, max) : text;
    }

    private record Operator(String name, String role) {
    }
}

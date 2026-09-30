# 结构化原始检定记录与版本留痕

## 数据模型（不是“一张明细表”）

- `lab_record_version`：每笔委托的原始记录版本。`DRAFT` 可编辑；提交后转为
  `SUBMITTED` 并记录 `submitted_at / locked_at / verifier_name`；核验后为
  `CONFIRMED`，退回为 `RETURNED`，误提交撤回为 `WITHDRAWN`，被新确认版替代为
  `SUPERSEDED`。生成列唯一约束保证每笔委托至多一个 `DRAFT`。
- `lab_record_item`：版本内数据项（校准点、标准值、示值、误差、单位、备注、行号），
  随版本整体冻结；新版本通过整行复制产生，旧版本行不更新、不删除。
- `lab_record_event`：只追加的事件流（保存、提交、通过、退回、撤回、修订、迁移、人员调整），
  含操作人、角色、原因、来源版本和时间。
- `lab_calibration_order` 增加：
  - `record_mode`：`LEGACY_TEXT / STRUCTURED / LEGACY_READONLY`
  - `current_record_version_id`：当前采用的已确认版本
  - `cert_record_version_id`：证书固定引用版本

## 关键服务端规则

- 草稿（仅 `CALIBRATING` 的被指派检定员）可批量增删、复制、粘贴，缺项允许先保存；
  提交时服务端强制至少一条数据、六项必填、结论合法、检定员/核验员均已指派且不是同一人。
- 提交后本版本锁定；核验员只能 `approve` 或带原因 `return`，不能改数据项。
- 退回/误提交撤回：旧版保留为 `RETURNED/WITHDRAWN`，自动复制出下一版 `DRAFT`。
- 出证前修订只能由被指派核验员或调度管理员带原因发起：旧确认版置 `SUPERSEDED`，
  复制新草稿，重新提交+核验通过后才能继续出证。
- 人员调整仅调度管理员可执行，必须写原因并留痕；未指派人员、跨角色人员一律拒绝。
- 核验通过时写入 `cert_record_version_id`；发放时再次校验该版本仍为 `CONFIRMED` 且为当前版本。
  已发放后禁止修订，后续更正须另开委托。
- 旧文本：进入核验即标记 `LEGACY_READONLY`；尚未核验者可由被指派检定员/调度管理员确认后
  迁移为第一版结构化草稿，`measured_data` 原文保留并标记历史；已核验/已发放旧委托只读。

## 接口（X-Operator-Name 请求头标识当前操作者，服务端最终判定）

- `POST   /api/calibration-orders/{id}/records/draft` 建立草稿
- `PUT    /api/calibration-orders/{id}/records/draft` 保存草稿
- `POST   /api/calibration-orders/{id}/records/submit` 提交并锁定
- `POST   /api/calibration-orders/{id}/records/approve` 核验通过
- `POST   /api/calibration-orders/{id}/records/return` 退回（reason 必填）
- `POST   /api/calibration-orders/{id}/records/withdraw` 误提交撤回
- `POST   /api/calibration-orders/{id}/records/revision` 出证前修订（reason 必填）
- `PUT    /api/calibration-orders/{id}/records/assignment` 人员调整
- `POST   /api/calibration-orders/{id}/records/migrate-legacy` 旧文本转换

## 前端

- 顶部选择当前操作人员（检定员/核验员/调度管理员），仅用于演示；权限以服务端为准。
- 草稿表格支持新增、单行复制、删除、批量粘贴（Tab / `|` / 逗号）。
- 版本时间线展示状态、提交/核验时间与人员、退回意见、逐行字段差异；
  明确标注“当前采用”和“证书固定”版本。
- 证书预览与发放数据来自 `certificateItems`（固定版本）；无固定版本时不给草稿数据。

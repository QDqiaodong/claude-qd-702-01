<template>
  <div class="split">
    <!-- 左：标准器挂接 + 竖向步骤 + 流转动作 + 结论录入 -->
    <section class="split-left panel">
      <div class="panel-head">
        <span class="panel-title">检定流转</span>
        <span class="spacer"></span>
        <el-button size="small" type="primary" plain @click="openNew">＋ 新建委托单</el-button>
      </div>

      <!-- 当前操作者：演示用本地选择，服务端按角色+指派最终判定 -->
      <div class="operator-bar">
        <span class="operator-label">当前操作人员</span>
        <el-select v-model="operatorName" size="small" filterable placeholder="选择操作者"
                   style="width: 168px" @change="onOperatorChange">
          <el-option-group v-for="g in operatorGroups" :key="g.role" :label="g.label">
            <el-option v-for="name in g.names" :key="name" :label="name" :value="name" />
          </el-option-group>
        </el-select>
        <el-tag size="small" :type="operatorRoleTag">{{ operatorRoleName || '未选择' }}</el-tag>
        <el-button v-if="canAssign" size="small" text type="primary" @click="openAssign">人员调整</el-button>
      </div>
      <el-alert v-if="records && records.actionHint" :closable="false" class="gate-alert"
                :type="records.operator && records.operator.authenticated ? 'info' : 'warning'"
                :title="records.actionHint" show-icon />

      <!-- ============ 标准器挂接与放行 ============ -->
      <div class="device-box">
        <div class="device-box-head">
          <span class="device-box-title">标准器挂接</span>
          <el-tag size="small" :type="deviceTagType">{{ order.deviceCount || 0 }} 件</el-tag>
          <span class="spacer"></span>
          <el-button
            v-if="order.devicesEditable"
            size="small" type="primary" plain
            @click="openDevicePicker"
          >选择 / 替换</el-button>
        </div>

        <!-- 作废态提示 -->
        <el-alert
          v-if="order.status === 'VOIDED'"
          type="info" :closable="false" show-icon
          title="本委托已作废，标准器占用已释放；记录保留可查，不能再流转或修改。"
          class="gate-alert"
        />

        <template v-else>
          <!-- 当前挂接清单（状态与有效期实时取自标准器台账） -->
          <div v-if="!order.devices || !order.devices.length" class="device-empty">
            尚未挂接标准器
            <span v-if="order.status === 'RECEIVED'" class="muted">· 收样环节可选</span>
          </div>
          <ul v-else class="device-list">
            <li v-for="d in order.devices" :key="d.linkId" :class="{ 'is-bad': !d.usable }">
              <div class="device-line">
                <b class="mono">{{ d.deviceNo }}</b>
                <span>{{ d.name }}</span>
                <span class="spacer"></span>
                <el-tag size="small" :type="deviceStatusTag(d)">{{ d.statusName }}</el-tag>
              </div>
              <div class="device-line sub">
                <span>证书有效期至 {{ d.certValidUntil || '未登记' }}</span>
                <span class="spacer"></span>
                <el-tag v-if="d.expired" size="small" type="danger">已过期 {{ Math.abs(d.remainDays) }} 天</el-tag>
                <el-tag v-else-if="d.expiring" size="small" type="warning">剩 {{ d.remainDays }} 天</el-tag>
                <el-tag v-else-if="d.remainDays !== null" size="small" type="success">剩 {{ d.remainDays }} 天</el-tag>
              </div>
              <div v-for="(iss, i) in d.issues" :key="i" class="device-issue">⛔ {{ iss }}</div>
            </li>
          </ul>

          <!-- 进入检定中后只读说明：不能静默换已使用的标准器 -->
          <div v-if="!order.devicesEditable && (!order.status || order.status !== 'ISSUED')" class="device-lock-note">
            🔒 已进入检定环节，已使用的标准器不能静默修改；如需更换，请先回退到收样（占用释放）后重新选择。
          </div>

          <!-- 已释放留痕 -->
          <el-collapse v-if="order.deviceHistory && order.deviceHistory.length" class="history-collapse">
            <el-collapse-item name="h">
              <template #title>
                <span class="muted">挂接变更记录（{{ order.deviceHistory.length }}）</span>
              </template>
              <div v-for="(h, i) in order.deviceHistory" :key="i" class="history-row">
                <span class="mono">{{ h.deviceNo }}</span>
                <span>{{ h.name }}</span>
                <el-tag size="small" effect="plain" type="info">{{ h.releasedReasonName }}</el-tag>
                <span class="spacer"></span>
                <span class="muted">{{ h.releasedAt }}</span>
              </div>
            </el-collapse-item>
          </el-collapse>
        </template>
      </div>

      <el-steps direction="vertical" :active="activeStep" finish-status="success" class="flow-steps">
        <el-step v-for="step in flowSteps" :key="step.key" :title="step.name">
          <template #description>
            <span class="flow-desc">{{ step.desc }}</span>
          </template>
        </el-step>
      </el-steps>

      <!-- 服务端判定的不可放行原因（推进失败后驻留，刷新/重复点击均无法绕过） -->
      <el-alert
        v-if="gateError"
        type="error" :closable="false" show-icon
        title="不可推进" class="gate-alert"
      >
        <div class="gate-error-msg">{{ gateError }}</div>
      </el-alert>
      <el-alert
        v-else-if="order.deviceBlocked && order.status !== 'ISSUED'"
        type="error" :closable="false" show-icon
        title="标准器当前不满足放行条件" class="gate-alert"
      >
        <div v-for="(r, i) in order.blockReasons" :key="i" class="gate-error-msg">⛔ {{ r }}</div>
        <div class="gate-error-tip">请处理（停用/送检设备回用、解除后改挂合格标准器）后再推进。</div>
      </el-alert>

      <div class="flow-actions">
        <el-button type="primary" size="small" :disabled="!canRollback" @click="rollback">◂ 回退</el-button>
        <el-button type="primary" size="small" :disabled="!canAdvance" @click="advance">推进 ▸</el-button>
        <el-button
          v-if="canVoid"
          type="danger" plain size="small"
          @click="voidOrder"
        >作废</el-button>
      </div>

      <div v-if="order.status !== 'VOIDED' && records" class="record-box">
        <div class="record-box-head">
          <span class="flow-form-title" style="margin:0">原始检定记录与版本留痕</span>
          <el-tag size="small" :type="records.structured ? 'success' : 'info'">{{ records.modeName }}</el-tag>
          <span class="spacer"></span>
          <el-tag v-if="records.currentVersionNo" size="small" type="success" effect="plain">
            当前确认 · 第 {{ records.currentVersionNo }} 版
          </el-tag>
          <el-tag v-if="records.certificateVersionNo" size="small" type="warning" effect="plain">
            证书固定 · 第 {{ records.certificateVersionNo }} 版
          </el-tag>
          <el-button v-if="records.structured && !draftVersion && !submittedVersion
                           && (order.status === 'RECEIVED' || order.status === 'CALIBRATING')
                           && records.operator && records.operator.authenticated
                           && !records.versions.length"
                     size="small" type="primary" plain style="margin-left:8px"
                     @click="createDraft">建立结构化草稿</el-button>
        </div>

        <!-- 旧文本历史记录 -->
        <template v-if="!records.structured">
          <el-alert
            :type="records.legacyReadonly ? 'error' : 'warning'"
            :closable="false" show-icon class="gate-alert"
            :title="records.legacyReadonly
              ? '这是升级前已核验或已发放的旧文本记录，只能只读保留，不能因升级改变原结论。'
              : '该委托的实测数据仍以旧文本保存，仅作历史记录展示；尚未进入核验，确认后可转换为第一版结构化记录。'" />
          <div v-if="records.legacyText" class="legacy-text">{{ records.legacyText.split(';').join('\n') }}</div>
          <div v-else class="device-empty">无旧文本实测数据</div>
          <div v-if="records.canMigrate" class="record-actions">
            <el-button size="small" type="primary" @click="confirmMigrate">
              确认转换为第一版结构化记录
            </el-button>
          </div>
        </template>

        <!-- 结构化记录 -->
        <template v-else>
          <!-- 草稿编辑 -->
          <div v-if="draftVersion" class="draft-area">
            <div class="draft-head">
              <b>第 {{ draftVersion.versionNo }} 版草稿</b>
              <span class="muted">草稿可批量增删 / 复制；提交后本版锁定，核验员不能替检定员改数</span>
            </div>

            <div class="draft-toolbar">
              <el-button size="small" @click="addRow">＋ 新增数据项</el-button>
              <el-button size="small" @click="openPaste">批量粘贴</el-button>
              <el-select v-model="draftForm.conclusion" size="small" placeholder="检定结论" style="width: 116px">
                <el-option label="合格" value="PASS" />
                <el-option label="限用" value="LIMITED" />
                <el-option label="不合格" value="FAIL" />
              </el-select>
              <span class="spacer"></span>
              <el-button size="small" @click="saveDraft" :loading="recordBusy">保存草稿</el-button>
              <el-button size="small" type="primary" @click="submitRecord" :loading="recordBusy">
                提交复核（锁定本版）
              </el-button>
            </div>

            <el-table :data="draftRows" size="small" border class="draft-table">
              <el-table-column label="#" width="40" type="index" />
              <el-table-column label="校准点" min-width="110">
                <template #default="{ row }"><el-input v-model="row.calibrationPoint" size="small" /></template>
              </el-table-column>
              <el-table-column label="标准值" min-width="100">
                <template #default="{ row }"><el-input v-model="row.standardValue" size="small" /></template>
              </el-table-column>
              <el-table-column label="示值" min-width="100">
                <template #default="{ row }"><el-input v-model="row.indicatedValue" size="small" /></template>
              </el-table-column>
              <el-table-column label="误差" min-width="90">
                <template #default="{ row }"><el-input v-model="row.errorValue" size="small" /></template>
              </el-table-column>
              <el-table-column label="单位" width="78">
                <template #default="{ row }"><el-input v-model="row.unit" size="small" /></template>
              </el-table-column>
              <el-table-column label="备注" min-width="120">
                <template #default="{ row }"><el-input v-model="row.remark" size="small" /></template>
              </el-table-column>
              <el-table-column label="操作" width="96">
                <template #default="{ $index }">
                  <el-button size="small" link type="primary" @click="copyRow($index)">复制</el-button>
                  <el-button size="small" link type="danger" @click="draftRows.splice($index, 1)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-input v-model="draftForm.recordRemark" size="small" class="draft-remark"
                      placeholder="整份原始记录备注（环境条件、异常情况等）" />
          </div>

          <!-- 待核验 -->
          <div v-else-if="submittedVersion" class="review-area">
            <el-alert type="warning" :closable="false" show-icon class="gate-alert"
                      :title="`第 ${submittedVersion.versionNo} 版已提交并锁定（提交人 ${submittedVersion.verifierName || '—'} · ${fmt(submittedVersion.submittedAt)}），核验员只能确认通过或写明原因退回。`" />
            <record-read-table :rows="submittedVersion.items" />
            <div class="record-actions">
              <el-button size="small" @click="openWithdraw" :disabled="!records.canWithdraw">
                误提交撤回（生成新草稿）
              </el-button>
              <span class="spacer"></span>
              <el-button size="small" type="danger" plain @click="openReturn" :disabled="!records.canReturn">
                退回并写明原因
              </el-button>
              <el-button size="small" type="success" @click="approveRecord" :disabled="!records.canApprove"
                         :loading="recordBusy">
                核验通过
              </el-button>
            </div>
          </div>

          <!-- 无草稿且非待核验：出证中/已发放等，给修订入口 -->
          <div v-else class="record-actions">
            <span class="muted">当前没有可编辑草稿。</span>
            <span class="spacer"></span>
            <el-button v-if="records.canStartRevision" size="small" type="warning" plain @click="openRevision">
              出证前发起修订（保留旧版 · 生成新草稿）
            </el-button>
          </div>

          <!-- 版本时间线 -->
          <el-divider content-position="left" class="record-divider">
            版本历史 · 退回意见 · 差异
          </el-divider>
          <el-timeline class="version-timeline">
            <el-timeline-item v-for="v in records.versions" :key="v.id" :timestamp="versionMeta(v)"
                              :type="versionDotType(v.status)" placement="top">
              <div class="version-row" :class="{ 'is-current': v.current || v.certificateVersion }">
                <el-tag size="small" :type="versionTagType(v.status)">{{ v.statusName }}</el-tag>
                <b>第 {{ v.versionNo }} 版</b>
                <el-tag v-if="v.current" size="small" type="success" effect="plain">当前采用</el-tag>
                <el-tag v-if="v.certificateVersion" size="small" type="warning" effect="plain">证书引用</el-tag>
                <span class="muted">来源：{{ v.sourceName }}<template v-if="v.sourceVersionNo">（自第 {{ v.sourceVersionNo }} 版）</template> · {{ v.itemCount }} 项</span>
                <span class="spacer"></span>
                <el-button size="small" link @click="toggleVersion(v)">
                  {{ openVersionId === v.id ? '收起' : '查看数据/差异' }}
                </el-button>
              </div>
              <div v-if="v.rejectReason" class="reject-reason">退回/撤回意见：{{ v.rejectReason }}</div>
              <div v-if="openVersionId === v.id" class="version-detail">
                <record-read-table :rows="v.items" />
                <div v-if="v.changes && v.changes.length" class="diff-box">
                  <div class="diff-title">与上一版差异</div>
                  <div v-for="(c, i) in v.changes" :key="i" class="diff-line">
                    <el-tag size="small" :type="diffTagType(c.changeType)">{{ c.changeTypeName }}</el-tag>
                    <span class="mono">第 {{ c.itemNo }} 行</span>
                    <span v-for="f in changedFields(c)" :key="f.field" class="diff-field">
                      {{ f.fieldName }}：<del>{{ f.before || '空' }}</del> → <b>{{ f.after || '空' }}</b>
                    </span>
                  </div>
                </div>
              </div>
            </el-timeline-item>
          </el-timeline>

          <!-- 事件流 -->
          <el-collapse class="history-collapse">
            <el-collapse-item name="e">
              <template #title><span class="muted">操作留痕（{{ records.events.length }}）</span></template>
              <div v-for="e in records.events" :key="e.id" class="history-row">
                <el-tag size="small" effect="plain">{{ e.eventTypeName }}</el-tag>
                <span>{{ e.actorName }}（{{ e.actorRoleName }}）</span>
                <span class="muted" style="flex:1">第 {{ e.versionNo || '—' }} 版<template v-if="e.fromVersionNo"> · 源自第 {{ e.fromVersionNo }} 版</template> · {{ e.detail }}</span>
                <span class="muted">{{ fmt(e.createdAt) }}</span>
              </div>
            </el-collapse-item>
          </el-collapse>
        </template>

        <!-- 证书编号（出证阶段，结构化与旧记录均保留） -->
        <el-form label-width="72px" size="small" class="cert-form">
          <el-form-item label="证书编号">
            <el-input v-model="form.certNo" placeholder="出证前必填" />
            <el-button size="small" style="margin-left:8px" @click="saveCertNo">保存证书编号</el-button>
          </el-form-item>
        </el-form>
      </div>

      <!-- 旧文本记录（非结构化且仍可编辑时保留快速文本框） -->
      <div v-else-if="order.status !== 'VOIDED'" class="flow-form">
        <div class="flow-form-title">实测数据（旧文本，历史格式）</div>
        <el-form label-width="72px" size="small">
          <el-form-item label="实测数据">
            <el-input v-model="form.measuredData" type="textarea" :rows="3"
                      placeholder="每行一条：校准点|标准值|示值|示值误差" />
          </el-form-item>
          <el-form-item label="检定结论">
            <el-select v-model="form.conclusion" placeholder="未判定" clearable style="width: 100%">
              <el-option label="合格" value="PASS" />
              <el-option label="限用" value="LIMITED" />
              <el-option label="不合格" value="FAIL" />
            </el-select>
          </el-form-item>
          <el-form-item label="证书编号">
            <el-input v-model="form.certNo" placeholder="出证前必填" />
          </el-form-item>
          <el-form-item>
            <el-button size="small" type="primary" @click="saveResult">保存旧文本数据</el-button>
          </el-form-item>
        </el-form>
      </div>
    </section>

    <!-- 右：A4 比例纸质证书预览卡片 -->
    <section class="split-right">
      <div class="cert-paper">
        <div class="cert-head">
          <div class="cert-org">
            <div class="cert-org-name">滨江市计量检定所</div>
            <div class="cert-org-sub">法定计量技术机构 · 授权证书号 (国)法计(2026) 第 0032 号</div>
          </div>
          <div class="cert-qr" title="证书二维码（占位）"></div>
        </div>

        <div class="cert-title">检 定 证 书</div>
        <div class="cert-no">
          <span>证书编号：{{ order.certNo || '待出证' }}</span>
          <span>委托单号：{{ order.orderNo || '—' }}</span>
        </div>
        <div class="cert-record-source">
          数据来源：{{ certDataSourceText }}
          <el-tag size="small" :type="records && records.structured ? 'success' : 'info'">
            {{ records && records.certificateVersionNo ? '固定引用第 ' + records.certificateVersionNo + ' 版'
              : (records && records.legacyReadonly ? '旧文本（已冻结）' : '旧文本历史记录') }}
          </el-tag>
        </div>

        <table class="cert-info">
          <tbody>
            <tr><th>委托单位</th><td>{{ order.clientName || '—' }}</td><th>器具编号</th><td class="mono">{{ order.instNo || '—' }}</td></tr>
            <tr><th>器具名称</th><td>{{ order.instrumentName || '—' }}</td><th>型号规格</th><td>{{ order.modelSpec || '—' }}</td></tr>
            <tr><th>制造厂</th><td>{{ order.manufacturer || '—' }}</td><th>出厂编号</th><td>{{ order.factoryNo || '—' }}</td></tr>
            <tr><th>测量范围</th><td>{{ order.measureRange || '—' }}</td><th>准确度等级</th><td>{{ order.accuracyGrade || '—' }}</td></tr>
            <tr><th>检定依据</th><td colspan="3">{{ procedureText }}</td></tr>
            <tr>
              <th>标准器</th>
              <td colspan="3">
                <template v-if="order.devices && order.devices.length">
                  <span v-for="(d, i) in order.devices" :key="d.linkId" :class="{ 'danger-text': !d.usable }">
                    {{ d.deviceNo }} {{ d.name }}<template v-if="i < order.devices.length - 1">；</template>
                  </span>
                </template>
                <span v-else class="muted">未挂接</span>
              </td>
            </tr>
          </tbody>
        </table>

        <div class="cert-subtitle">检定结果（环境条件：{{ envText }}）</div>
        <table class="cert-data">
          <thead>
            <tr><th>校准点</th><th>标准值</th><th>示值</th><th>示值误差</th><th>单位</th></tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in certRows" :key="i">
              <td>{{ row.calibrationPoint || '—' }}</td>
              <td>{{ row.standardValue || '—' }}</td>
              <td>{{ row.indicatedValue || '—' }}</td>
              <td>{{ row.errorValue || '—' }}</td>
              <td>{{ row.unit || '—' }}</td>
            </tr>
            <tr v-if="!certRows.length">
              <td colspan="5" class="cert-empty">{{ certDataEmptyText }}</td>
            </tr>
          </tbody>
        </table>

        <div class="cert-concl">
          检定结论：<b :class="conclusionClass">{{ certConclusionText }}</b>
          <span v-if="order.remark" class="cert-concl-note">（{{ order.remark }}）</span>
        </div>

        <div class="cert-stamp" :class="{ 'is-none': stampFaded, 'is-void': order.status === 'VOIDED' }">
          <span class="cert-stamp-big">{{ stampText }}</span>
          <span class="cert-stamp-small">{{ order.status === 'VOIDED' ? '作废' : '计量检定专用章' }}</span>
        </div>

        <div class="cert-sign">
          <div><span>检定员</span><b>{{ order.verifier || '待指派' }}</b></div>
          <div><span>核验员</span><b>{{ order.checker || '待指派' }}</b></div>
          <div><span>批准人</span><b>{{ approver }}</b></div>
        </div>

        <div class="cert-foot">
          <span>证书有效期至：{{ order.certValidUntil || '出证后按检定周期核定' }}</span>
          <span>记录版本：{{ records && records.certificateVersionNo ? 'V' + records.certificateVersionNo : (records && records.legacyReadonly ? '旧文本' : '未固定') }}</span>
          <span>第 1 页 共 1 页</span>
        </div>
      </div>
    </section>

    <!-- ============ 标准器批量选择 / 替换（仅收样态） ============ -->
    <el-dialog v-model="pickerVisible" title="选择参与本次检定的标准器" width="760px" @closed="onPickerClosed">
      <div class="picker-tip muted">
        可批量勾选；被其他未完成委托占用的标准器不可抢占（灰色）；停用、送检或证书过期的标准器可挂但会被放行校验拦下。
        最终占用以服务端判定为准。
      </div>

      <el-alert
        v-if="pickerConflicts.length"
        type="error" :closable="false" show-icon
        title="以下标准器被占用，本委托不能抢占；您原来选择的其他标准器仍保留，请改选后重新提交"
        class="gate-alert"
      >
        <div v-for="(c, i) in pickerConflicts" :key="i" class="gate-error-msg">⛔ {{ c }}</div>
      </el-alert>

      <el-table
        ref="pickerTableRef"
        :data="deviceOptions"
        size="small" border stripe height="380"
        row-key="id"
        @selection-change="onSelectionChange"
        :row-class-name="pickerRowClass"
      >
        <el-table-column type="selection" width="46" :selectable="canSelect" reserve-selection />
        <el-table-column prop="deviceNo" label="编号" width="104">
          <template #default="{ row }"><span class="mono">{{ row.deviceNo }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
        <el-table-column prop="model" label="型号" width="120" />
        <el-table-column label="证书有效期至" width="118">
          <template #default="{ row }">{{ row.certValidUntil || '未登记' }}</template>
        </el-table-column>
        <el-table-column label="剩余" width="104">
          <template #default="{ row }">
            <el-tag v-if="row.expired" size="small" type="danger">过期</el-tag>
            <el-tag v-else-if="row.expiring" size="small" type="warning">{{ row.remainDays }} 天</el-tag>
            <el-tag v-else-if="row.remainDays !== null" size="small" type="success">{{ row.remainDays }} 天</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="82">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'IN_USE' ? 'success' : row.status === 'TRACING' ? 'warning' : 'info'">
              {{ row.statusName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="占用/说明" min-width="170">
          <template #default="{ row }">
            <el-tag v-if="row.busy" size="small" type="danger" disable-transitions>
              {{ row.busyOrderNo }} 占用
            </el-tag>
            <span v-else-if="row.issue" class="danger-text">{{ row.issue }}</span>
            <el-tag v-else size="small" type="success">可选用</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <template #footer>
        <span class="muted picker-count">已选 {{ pickedIds.length }} 件</span>
        <el-button size="small" @click="pickerVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="savingDevices" @click="submitDevices">
          保存挂接清单
        </el-button>
      </template>
    </el-dialog>

    <!-- ============ 批量粘贴 ============ -->
    <el-dialog v-model="pasteVisible" title="批量粘贴数据项" width="620px">
      <div class="muted picker-tip">
        支持 Excel/WPS 复制的多列（Tab 分隔）或文本多列（| 、逗号分隔）。
        每行按「校准点 / 标准值 / 示值 / 误差 / 单位 / 备注」解析；这只是录入便利，提交与版本仍由服务端判定。
      </div>
      <el-input v-model="pasteText" type="textarea" :rows="9"
                placeholder="0&#9;+0.000&#9;+0.001&#9;+0.001&#9;mm&#10;或：0|+0.000|+0.001|+0.001|mm|零点检查" />
      <template #footer>
        <el-button size="small" @click="pasteVisible = false">取消</el-button>
        <el-button size="small" type="primary" @click="applyPaste">追加到草稿</el-button>
      </template>
    </el-dialog>

    <!-- ============ 退回 / 撤回 / 修订原因 ============ -->
    <el-dialog v-model="reasonVisible" :title="reasonDialog.title" width="480px">
      <el-input v-model="reasonDialog.text" type="textarea" :rows="4"
                :placeholder="reasonDialog.placeholder" />
      <template #footer>
        <el-button size="small" @click="reasonVisible = false">取消</el-button>
        <el-button size="small" :type="reasonDialog.type" @click="confirmReason">确认</el-button>
      </template>
    </el-dialog>

    <!-- ============ 人员调整（仅调度管理员） ============ -->
    <el-dialog v-model="assignVisible" title="人员调整（留痕，不替他人操作版本）" width="480px">
      <el-form label-width="88px" size="small">
        <el-form-item label="检定员">
          <el-select v-model="assignForm.verifier" filterable clearable style="width:100%">
            <el-option v-for="n in verifierNames" :key="n" :label="n" :value="n" />
          </el-select>
        </el-form-item>
        <el-form-item label="核验员">
          <el-select v-model="assignForm.checker" filterable clearable style="width:100%">
            <el-option v-for="n in checkerNames" :key="n" :label="n" :value="n" />
          </el-select>
        </el-form-item>
        <el-form-item label="调整原因">
          <el-input v-model="assignForm.reason" type="textarea" :rows="3" placeholder="如：人员请假调岗、指派错误更正" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="assignVisible = false">取消</el-button>
        <el-button size="small" type="primary" @click="submitAssign">保存调整</el-button>
      </template>
    </el-dialog>

    <!-- 新建委托单 -->
    <el-dialog v-model="showNew" title="新建检定委托单（按器具编号受理）" width="520px">
      <el-form label-width="96px" size="small">
        <el-form-item label="委托单号">
          <el-input v-model="newForm.orderNo" />
        </el-form-item>
        <el-form-item label="器具编号">
          <el-select v-model="newForm.instNo" filterable placeholder="选器具编号" style="width: 100%">
            <el-option v-for="it in instruments" :key="it.instNo" :label="it.instNo + ' · ' + it.name" :value="it.instNo" />
          </el-select>
        </el-form-item>
        <el-form-item label="检定规程">
          <el-select v-model="newForm.procNo" filterable placeholder="选规程号" style="width: 100%">
            <el-option v-for="p in procedures" :key="p.procNo" :label="p.procNo + ' · ' + p.name" :value="p.procNo" />
          </el-select>
        </el-form-item>
        <el-form-item label="收样日期">
          <el-date-picker v-model="newForm.receiveDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="检定员">
          <el-input v-model="newForm.verifier" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="newForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="showNew = false">取消</el-button>
        <el-button size="small" type="primary" @click="submitNew">受理并生成流转单</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, reactive, ref, watch, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { instrumentApi, orderApi, procedureApi } from '../api'
import RecordReadTable from './RecordReadTable.vue'

const lab = inject('lab')
const order = computed(() => lab.currentOrder.value || {})

const FLOW = [
  { key: 'RECEIVED', name: '收样' },
  { key: 'CALIBRATING', name: '检定中' },
  { key: 'VERIFYING', name: '核验' },
  { key: 'CERTIFYING', name: '出证' },
  { key: 'ISSUED', name: '已发放' }
]

const flowSteps = computed(() => {
  const index = order.value.stepIndex == null ? 0 : order.value.stepIndex
  return FLOW.map((step, i) => ({
    ...step,
    desc: stepDesc(step.key, index, i)
  }))
})

function stepDesc(key, index, i) {
  if (order.value.id == null) return '—'
  if (order.value.status === 'VOIDED') return '已作废'
  if (i < index) return '已完成 · ' + (i === 0 ? (order.value.receiveDate || '已收样') : '流转留痕')
  if (i === index) return '当前环节 · 待处理'
  return '待流转'
}

const activeStep = computed(() => {
  if (order.value.id == null) return -1
  if (order.value.status === 'VOIDED') return -1
  return order.value.stepIndex == null ? 0 : order.value.stepIndex
})

const canAdvance = computed(() => {
  if (order.value.id == null || order.value.status === 'ISSUED' || order.value.status === 'VOIDED') return false
  // 结构化记录必须走“提交核验 → 核验通过”的版本接口，普通推进按钮只保留收样→检定中
  if (records.value?.structured) {
    return order.value.status === 'RECEIVED'
  }
  return true
})
const canRollback = computed(() =>
  order.value.id != null && order.value.status !== 'RECEIVED'
  && order.value.status !== 'ISSUED' && order.value.status !== 'VOIDED'
  && !(records.value?.structured && (order.value.status === 'VERIFYING' || order.value.status === 'CERTIFYING')))
const canVoid = computed(() =>
  order.value.id != null && order.value.status !== 'ISSUED' && order.value.status !== 'VOIDED')

const deviceTagType = computed(() => {
  if (!order.value.deviceCount) return 'info'
  return order.value.deviceBlocked ? 'danger' : 'success'
})

function deviceStatusTag(d) {
  if (d.status === 'IN_USE') return 'success'
  if (d.status === 'TRACING') return 'warning'
  return 'info'
}

const instruments = ref([])
const procedures = ref([])
const procMap = computed(() => {
  const map = {}
  procedures.value.forEach((p) => (map[p.procNo] = p))
  return map
})

const procedureText = computed(() => {
  const no = order.value.procNo
  if (!no) return '—'
  const p = procMap.value[no]
  return p ? p.procNo + ' ' + p.name + '（收费 ' + p.unitFee + ' 元）' : no
})

const envText = computed(() => {
  const p = procMap.value[order.value.procNo]
  return p && p.envCondition ? p.envCondition : '温度 (20±5)℃，湿度 ≤70%RH'
})

/** 原始记录聚合（详情接口返回；列表接口为空） */
const records = computed(() => order.value.records || null)

/** 证书预览与最终发放固定使用证书引用版本，绝不跟随草稿或后续修订变化 */
const certRows = computed(() => records.value?.certificateItems || [])
const certDataSourceText = computed(() => records.value?.certificateDataSourceName || '旧文本历史记录')
const certDataEmptyText = computed(() => {
  if (!records.value) return '尚未录入实测数据'
  if (records.value.structured && !records.value.certificateVersionNo) return '尚无已确认并固定到证书的版本'
  return records.value.legacyText ? '旧文本未能拆分为数据行' : '尚未录入实测数据'
})

const conclusionText = computed(() => {
  const map = { PASS: '合格', LIMITED: '限用', FAIL: '不合格' }
  return order.value.conclusion ? map[order.value.conclusion] : '未判定'
})
const certConclusionText = computed(() => {
  const map = { PASS: '合格', LIMITED: '限用', FAIL: '不合格' }
  const c = records.value?.certificateConclusion || order.value.conclusion
  return c ? map[c] || c : '未判定'
})

const conclusionClass = computed(() => ({
  'ok-text': order.value.conclusion === 'PASS',
  'warn-text': order.value.conclusion === 'LIMITED',
  'danger-text': order.value.conclusion === 'FAIL'
}))

const stampFaded = computed(() =>
  order.value.status === 'RECEIVED' || order.value.status === 'CALIBRATING')
const stampText = computed(() => {
  if (order.value.status === 'VOIDED') return '作 废'
  if (stampFaded.value) return '未盖章'
  return conclusionText.value
})

const approver = computed(() => {
  if (order.value.status === 'ISSUED' || order.value.status === 'CERTIFYING') return '所技术负责人'
  return '待签发'
})

const form = reactive({ measuredData: '', conclusion: '', certNo: '', verifier: '', checker: '' })

// ================= 操作者 =================
const STAFF = [
  { role: 'VERIFIER', label: '检定员', names: ['赵砚青', '陈听澜', '林砚秋', '周慕言'] },
  { role: 'CHECKER', label: '核验员', names: ['孙敬之', '王核验'] },
  { role: 'COORDINATOR', label: '调度管理员', names: ['调度管理员'] }
]
const operatorGroups = STAFF
const verifierNames = STAFF[0].names
const checkerNames = STAFF[1].names
const operatorName = ref(localStorage.getItem('operatorName') || '')
const operatorRole = computed(() => {
  const hit = STAFF.find((g) => g.names.includes(operatorName.value))
  return hit ? hit.role : ''
})
const operatorRoleName = computed(() => {
  const hit = STAFF.find((g) => g.names.includes(operatorName.value))
  return hit ? hit.label : ''
})
const operatorRoleTag = computed(
  () => ({ VERIFIER: 'primary', CHECKER: 'success', COORDINATOR: 'warning' }[operatorRole.value] || 'info'))

function onOperatorChange() {
  if (operatorName.value) localStorage.setItem('operatorName', operatorName.value)
  else localStorage.removeItem('operatorName')
  lab.refreshAll()
}

// ================= 结构化记录 =================
const recordBusy = ref(false)
const openVersionId = ref(null)
const draftRows = ref([])
const draftForm = reactive({ conclusion: '', recordRemark: '' })

const draftVersion = computed(() =>
  records.value?.versions?.find((v) => v.status === 'DRAFT' && records.value.draftVersionId === v.id)
  || records.value?.versions?.find((v) => v.status === 'DRAFT') || null)
const submittedVersion = computed(() =>
  records.value?.versions?.filter((v) => v.status === 'SUBMITTED')
    .sort((a, b) => b.versionNo - a.versionNo)[0] || null)
const canAssign = computed(() => records.value?.canAssign)

function emptyRow() {
  return { rowKey: 'r' + Date.now() + Math.random().toString(16).slice(2, 7),
           calibrationPoint: '', standardValue: '', indicatedValue: '',
           errorValue: '', unit: '', remark: '' }
}

function addRow() {
  draftRows.value.push(emptyRow())
}

function copyRow(index) {
  const src = draftRows.value[index]
  draftRows.value.splice(index + 1, 0, { ...emptyRow(), ...src })
}

function loadDraftForm() {
  const d = draftVersion.value
  draftRows.value = d ? d.items.map((r) => ({ ...r, rowKey: r.rowKey || ('r' + r.id) })) : []
  draftForm.conclusion = d?.conclusion || ''
  draftForm.recordRemark = d?.recordRemark || ''
}

function draftPayload() {
  return {
    items: draftRows.value.map((r) => ({
      rowKey: r.rowKey,
      calibrationPoint: r.calibrationPoint,
      standardValue: r.standardValue,
      indicatedValue: r.indicatedValue,
      errorValue: r.errorValue,
      unit: r.unit,
      remark: r.remark
    })),
    conclusion: draftForm.conclusion || null,
    recordRemark: draftForm.recordRemark || null
  }
}

async function reloadRecord() {
  await lab.refreshAll()
}

async function createDraft() {
  await orderApi.createDraft(order.value.id)
  ElMessage.success('已建立结构化原始记录草稿')
  await reloadRecord()
}

async function saveDraft() {
  if (!draftVersion.value) return
  recordBusy.value = true
  try {
    await orderApi.saveDraft(order.value.id, draftPayload())
    ElMessage.success('草稿已保存，尚未锁定')
    await reloadRecord()
  } finally { recordBusy.value = false }
}

async function submitRecord() {
  if (!draftVersion.value) return
  recordBusy.value = true
  try {
    await orderApi.submitRecord(order.value.id, draftPayload())
    ElMessage.success('本版本已提交复核并锁定')
    await reloadRecord()
  } finally { recordBusy.value = false }
}

async function approveRecord() {
  recordBusy.value = true
  try {
    await orderApi.approveRecord(order.value.id)
    ElMessage.success('核验通过，证书已固定引用该版本')
    await reloadRecord()
  } finally { recordBusy.value = false }
}

const reasonVisible = ref(false)
const reasonDialog = reactive({ mode: '', title: '', text: '', placeholder: '', type: 'primary' })

function openReturn() {
  reasonDialog.mode = 'return'
  reasonDialog.title = '核验退回（写明原因，检定员据此修订）'
  reasonDialog.placeholder = '例如：第 2 行单位缺失；300℃ 点需复测并补充环境条件备注。'
  reasonDialog.text = ''
  reasonDialog.type = 'danger'
  reasonVisible.value = true
}
function openWithdraw() {
  reasonDialog.mode = 'withdraw'
  reasonDialog.title = '撤回误提交版本'
  reasonDialog.placeholder = '可填写误提交原因（选填）；旧版本将锁定留痕并复制新草稿。'
  reasonDialog.text = ''
  reasonDialog.type = 'warning'
  reasonVisible.value = true
}
function openRevision() {
  reasonDialog.mode = 'revision'
  reasonDialog.title = '出证前发起修订'
  reasonDialog.placeholder = '必须写明修订原因；已确认旧版保留为“已被替代”，不得删除。'
  reasonDialog.text = ''
  reasonDialog.type = 'warning'
  reasonVisible.value = true
}

async function confirmReason() {
  const mode = reasonDialog.mode
  const text = reasonDialog.text.trim()
  if (mode === 'return' && !text) { ElMessage.warning('退回必须写明原因'); return }
  if (mode === 'revision' && !text) { ElMessage.warning('发起修订必须写明原因'); return }
  reasonVisible.value = false
  recordBusy.value = true
  try {
    if (mode === 'return') {
      await orderApi.returnRecord(order.value.id, text)
      ElMessage.success('已退回并生成新版本草稿')
    } else if (mode === 'withdraw') {
      await orderApi.withdrawRecord(order.value.id, text || null)
      ElMessage.success('已撤回并复制新草稿')
    } else {
      await orderApi.startRevision(order.value.id, text)
      ElMessage.success('已发起修订并生成新草稿')
    }
    await reloadRecord()
  } finally { recordBusy.value = false }
}

// 批量粘贴
const pasteVisible = ref(false)
const pasteText = ref('')
function openPaste() { pasteText.value = ''; pasteVisible.value = true }
function parsePaste(text) {
  const rows = []
  text.split(/\r?\n/).forEach((line) => {
    if (!line.trim()) return
    const parts = line.includes('\t') ? line.split('\t')
      : line.includes('|') ? line.split('|') : line.split(/[，,]/)
    const v = parts.map((p) => p.trim())
    rows.push({ ...emptyRow(),
      calibrationPoint: v[0] || '', standardValue: v[1] || '', indicatedValue: v[2] || '',
      errorValue: v[3] || '', unit: v[4] || '', remark: v[5] || '' })
  })
  return rows
}
function applyPaste() {
  const parsed = parsePaste(pasteText.value)
  if (!parsed.length) { ElMessage.warning('未解析到有效行'); return }
  draftRows.value.push(...parsed)
  pasteVisible.value = false
  ElMessage.success(`已追加 ${parsed.length} 行，请核对后保存`)
}

// 人员调整
const assignVisible = ref(false)
const assignForm = reactive({ verifier: '', checker: '', reason: '' })
function openAssign() {
  assignForm.verifier = order.value.verifier && order.value.verifier !== '待分配' ? order.value.verifier : ''
  assignForm.checker = order.value.checker && order.value.checker !== '待分配' ? order.value.checker : ''
  assignForm.reason = ''
  assignVisible.value = true
}
async function submitAssign() {
  if (!assignForm.reason.trim()) { ElMessage.warning('人员调整必须写明原因'); return }
  if (assignForm.verifier && assignForm.verifier === assignForm.checker) {
    ElMessage.warning('检定员与核验员不能是同一人'); return
  }
  await orderApi.assign(order.value.id, { ...assignForm })
  assignVisible.value = false
  ElMessage.success('人员调整已记录')
  await reloadRecord()
}

// 旧文本迁移
async function confirmMigrate() {
  try {
    await ElMessageBox.confirm(
      '将把旧文本按分隔符拆分为第一版结构化草稿（原文保留并标记为历史记录）。转换后仍需逐项核对、补齐单位与备注。是否继续？',
      '旧文本转换确认', { type: 'warning', confirmButtonText: '确认转换', cancelButtonText: '取消' })
  } catch { return }
  await orderApi.migrateLegacy(order.value.id, operatorName.value)
  ElMessage.success('已转换为第一版结构化草稿')
  await reloadRecord()
}

// 版本展示辅助
function toggleVersion(v) { openVersionId.value = openVersionId.value === v.id ? null : v.id }
function fmt(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '—' }
function versionMeta(v) {
  const parts = [`提交 ${fmt(v.submittedAt)}`, `核验 ${fmt(v.reviewedAt)}`]
  if (v.verifierName) parts.push(`检定员 ${v.verifierName}`)
  if (v.checkerName) parts.push(`核验员 ${v.checkerName}`)
  return parts.join(' · ')
}
function versionTagType(s) {
  return { DRAFT: 'info', SUBMITTED: 'warning', RETURNED: 'danger', CONFIRMED: 'success',
           SUPERSEED: 'info', SUPERSEDED: 'info', WITHDRAWN: 'info' }[s] || 'info'
}
function versionDotType(s) {
  return { DRAFT: 'info', SUBMITTED: 'warning', RETURNED: 'danger', CONFIRMED: 'success',
           SUPERSEDED: 'info', WITHDRAWN: 'info' }[s] || 'info'
}
function diffTagType(t) {
  return { ADDED: 'success', REMOVED: 'danger', MODIFIED: 'warning', SAME: 'info' }[t] || 'info'
}
function changedFields(c) {
  return (c.fields || []).filter((f) => f.changed)
}

watch(
  () => [order.value.id, draftVersion.value?.id],
  () => loadDraftForm(),
  { immediate: true }
)

watch(
  order,
  (val) => {
    form.measuredData = val && val.measuredData ? val.measuredData.split(';').join('\n') : ''
    form.conclusion = val && val.conclusion ? val.conclusion : ''
    form.certNo = val && val.certNo ? val.certNo : ''
    form.verifier = val && val.verifier ? val.verifier : ''
    form.checker = val && val.checker ? val.checker : ''
    // 切换委托后清掉上一笔的推进报错（新一笔的状态以详情接口实时结果为准）
    gateError.value = ''
  },
  { immediate: true }
)

async function saveResult() {
  if (!order.value.id) return
  const payload = {
    measuredData: form.measuredData.split('\n').map((s) => s.trim()).filter(Boolean).join(';'),
    conclusion: form.conclusion || null,
    certNo: form.certNo || null,
    verifier: form.verifier || null,
    checker: form.checker || null
  }
  await orderApi.result(order.value.id, payload)
  ElMessage.success('实测数据与结论已保存')
  await lab.refreshAll()
}

/** 证书编号属于证书元数据，不改动任何记录版本的数据项 */
async function saveCertNo() {
  if (!order.value.id) return
  await orderApi.result(order.value.id, { certNo: form.certNo || null })
  ElMessage.success('证书编号已保存（不影响已固定的记录版本）')
  await lab.refreshAll()
}

/** 推进失败信息驻留页面：只以服务端最新判定为准，任何前端操作都改不掉它 */
const gateError = ref('')
const advancing = ref(false)

async function advance() {
  if (advancing.value) return // 防重复点击
  advancing.value = true
  gateError.value = ''
  try {
    await orderApi.advance(order.value.id)
    ElMessage.success('流转推进一格')
    await lab.refreshAll()
  } catch (e) {
    // 服务端点名哪件标准器导致失败；重新拉详情，让页面状态与后端完全一致
    gateError.value = e.message || '服务端放行校验未通过'
    await lab.refreshAll()
  } finally {
    advancing.value = false
  }
}

async function rollback() {
  await orderApi.rollback(order.value.id)
  ElMessage.success('流转回退一格')
  gateError.value = ''
  await lab.refreshAll()
}

async function voidOrder() {
  try {
    await ElMessageBox.confirm(
      '作废后委托不可再流转，已挂标准器将立即释放，记录保留可查。确认作废？',
      '委托作废确认', { type: 'warning', confirmButtonText: '确认作废', cancelButtonText: '取消' })
  } catch {
    return
  }
  await orderApi.void(order.value.id)
  ElMessage.success('委托已作废，标准器占用已释放')
  gateError.value = ''
  await lab.refreshAll()
}

// ================= 标准器选择 / 批量挂接 / 替换 / 解除 =================
const pickerVisible = ref(false)
const deviceOptions = ref([])
const pickedIds = ref([])
const pickerConflicts = ref([])
const savingDevices = ref(false)
const pickerTableRef = ref(null)

async function openDevicePicker() {
  if (!order.value.id) return
  pickerConflicts.value = []
  deviceOptions.value = await orderApi.deviceOptions(order.value.id)
  pickerVisible.value = true
  await nextTick()
  // 回显当前已挂接（占用中）的标准器
  pickedIds.value = deviceOptions.value.filter((d) => d.selected).map((d) => d.id)
  deviceOptions.value.forEach((row) => {
    if (row.selected) pickerTableRef.value?.toggleRowSelection(row, true)
  })
}

function onSelectionChange(rows) {
  pickedIds.value = rows.map((r) => r.id)
}

/** 被别的委托占用的行禁选（不可抢占）；本笔已选/失效设备仍允许勾选调整 */
function canSelect(row) {
  return !row.busy
}

function pickerRowClass({ row }) {
  return row.busy ? 'row-busy' : ''
}

async function submitDevices() {
  if (savingDevices.value) return
  savingDevices.value = true
  try {
    await orderApi.saveDevices(order.value.id, pickedIds.value)
    ElMessage.success('标准器挂接清单已保存')
    pickerConflicts.value = []
    pickerVisible.value = false
    gateError.value = ''
    await lab.refreshAll()
  } catch (e) {
    // 并发抢占失败：服务端整单回滚，原委托数据与原挂接不变；冲突逐件驻留供改选
    pickerConflicts.value = e.conflicts && e.conflicts.length ? e.conflicts : [e.message || '保存失败']
    // 刷新候选项的占用方，但保留用户当前勾选（失败的一方可重新选择）
    const fresh = await orderApi.deviceOptions(order.value.id)
    const stillPicked = new Set(pickedIds.value)
    deviceOptions.value = fresh
    await nextTick()
    deviceOptions.value.forEach((row) => {
      if (stillPicked.has(row.id) && !row.busy) {
        pickerTableRef.value?.toggleRowSelection(row, true)
      }
    })
  } finally {
    savingDevices.value = false
  }
}

function onPickerClosed() {
  pickerConflicts.value = []
  pickedIds.value = []
  pickerTableRef.value?.clearSelection()
}

const showNew = ref(false)
const newForm = reactive({
  orderNo: '',
  instNo: '',
  procNo: '',
  receiveDate: '',
  verifier: '',
  remark: ''
})

function nextOrderNo() {
  const y = new Date().getFullYear()
  const seq = (lab.orderList.value || []).length + 1
  return 'WT' + y + '-' + String(seq).padStart(4, '0')
}

function today() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

async function openNew() {
  await loadOptions()
  newForm.orderNo = nextOrderNo()
  newForm.receiveDate = today()
  showNew.value = true
}

async function submitNew() {
  if (!newForm.instNo || !newForm.procNo) {
    ElMessage.warning('请选择器具编号与检定规程')
    return
  }
  const created = await orderApi.create({ ...newForm })
  showNew.value = false
  ElMessage.success('委托单已受理，流转状态：收样')
  await lab.refreshAll()
  await lab.selectOrder(created.id)
}

async function loadOptions() {
  const [its, pros] = await Promise.all([instrumentApi.list(), procedureApi.list()])
  instruments.value = its
  procedures.value = pros
}

onMounted(loadOptions)
</script>

<style scoped>
/* ---------- 标准器挂接区 ---------- */
.device-box {
  border: 1px solid var(--mt-border);
  border-radius: 8px;
  padding: 10px;
  margin-bottom: 12px;
  background: #fbfaff;
}

.device-box-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.device-box-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--mt-primary);
}

.device-empty {
  font-size: 12px;
  color: var(--mt-text-sub);
  padding: 6px 2px;
}

.device-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.device-list li {
  border: 1px solid var(--mt-border);
  border-radius: 6px;
  padding: 6px 8px;
  background: #fff;
}

.device-list li.is-bad {
  border-color: #e6a0a0;
  background: #fdf3f3;
}

.device-line {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.device-line.sub {
  color: var(--mt-text-sub);
  margin-top: 3px;
}

.device-line .spacer,
.history-row .spacer {
  flex: 1;
}

.device-issue {
  font-size: 12px;
  color: var(--mt-danger);
  margin-top: 4px;
  line-height: 1.4;
}

.device-lock-note {
  font-size: 11px;
  color: var(--mt-warn);
  margin-top: 8px;
  line-height: 1.5;
}

.history-collapse {
  margin-top: 6px;
}

.history-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  padding: 3px 0;
}

.gate-alert {
  margin: 8px 0;
}

.gate-error-msg {
  font-size: 12px;
  line-height: 1.6;
}

.gate-error-tip {
  font-size: 12px;
  margin-top: 4px;
  opacity: 0.85;
}

.flow-steps {
  padding: 4px 0;
}

.flow-desc {
  font-size: 12px;
  color: var(--mt-text-sub);
}

.flow-actions {
  display: flex;
  gap: 8px;
  padding: 12px 0 4px;
  border-top: 1px dashed var(--mt-border);
}

.flow-form-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--mt-primary);
  margin: 6px 0 10px;
}

/* ---------- 原始记录与版本 ---------- */
.operator-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 2px 10px;
  flex-wrap: wrap;
}

.operator-label {
  font-size: 12px;
  color: var(--mt-text-sub);
}

.record-box {
  border-top: 1px dashed var(--mt-border);
  padding-top: 10px;
}

.record-box-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.record-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 6px 0;
  flex-wrap: wrap;
}

.legacy-text {
  white-space: pre-wrap;
  background: #faf7ee;
  border: 1px solid #e3dcc7;
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 12px;
  line-height: 1.7;
  color: #5c5344;
  margin: 6px 0;
}

.draft-area {
  border: 1px solid var(--mt-border);
  border-radius: 8px;
  padding: 8px;
  background: #fcfcff;
}

.draft-head {
  display: flex;
  gap: 8px;
  align-items: baseline;
  font-size: 13px;
  margin-bottom: 6px;
}

.draft-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.draft-table {
  width: 100%;
}

.draft-remark {
  margin-top: 8px;
}

.record-divider {
  margin: 12px 0 8px;
}

.version-timeline {
  padding-left: 4px;
  margin-top: 4px;
}

.version-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  flex-wrap: wrap;
}

.version-row.is-current {
  background: #f3f8ff;
  border-radius: 4px;
  padding: 2px 4px;
}

.reject-reason {
  margin: 4px 0;
  padding: 6px 8px;
  background: #fdf3f3;
  border-left: 3px solid var(--mt-danger);
  font-size: 12px;
  color: var(--mt-danger);
  border-radius: 0 4px 4px 0;
}

.version-detail {
  margin-top: 6px;
}

.diff-box {
  border: 1px dashed var(--mt-border);
  border-radius: 6px;
  padding: 6px 8px;
  background: #fafafa;
}

.diff-title {
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 4px;
}

.diff-line {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  padding: 2px 0;
  flex-wrap: wrap;
}

.diff-field del {
  color: var(--mt-text-sub);
  margin-right: 2px;
}

.cert-form {
  margin-top: 8px;
}

.cert-record-source {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 10px;
  color: #6a6357;
  margin: 4px 0 8px;
}

/* ---------- 标准器选择弹窗 ---------- */
.picker-tip {
  font-size: 12px;
  margin-bottom: 8px;
  line-height: 1.5;
}

.picker-count {
  margin-right: 12px;
}

:deep(.row-busy) {
  background: #f6f6f8 !important;
  color: var(--mt-text-sub);
}

/* ---------- A4 纸质证书预览卡片 ---------- */
.cert-paper {
  position: relative;
  width: 100%;
  max-width: 452px;
  margin: 0 auto;
  aspect-ratio: 1 / 1.414;
  background: #fffdf8;
  border: 1px solid #d8d2c4;
  border-radius: 2px;
  box-shadow: 0 6px 18px rgba(60, 50, 30, 0.14);
  padding: 22px 24px 16px;
  display: flex;
  flex-direction: column;
  font-size: 12px;
  color: #23201c;
  overflow: hidden;
}

/* 纸张底纹：细横线模拟证书纸的安全底印 */
.cert-paper::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image: linear-gradient(rgba(69, 39, 160, 0.035) 1px, transparent 1px);
  background-size: 100% 22px;
  pointer-events: none;
}

.cert-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  border-bottom: 2px solid #4527a0;
  padding-bottom: 8px;
}

.cert-org-name {
  font-size: 17px;
  font-weight: 700;
  letter-spacing: 3px;
  color: #4527a0;
}

.cert-org-sub {
  font-size: 10px;
  color: #7a7368;
  margin-top: 3px;
}

.cert-qr {
  margin-left: auto;
  width: 56px;
  height: 56px;
  flex: 0 0 56px;
  border: 1px solid #8d8d8d;
  background-color: #fff;
  background-image:
    repeating-linear-gradient(0deg, #2b2b2b 0 2px, transparent 2px 4px),
    repeating-linear-gradient(90deg, #2b2b2b 0 2px, transparent 2px 4px);
  background-size: 100% 4px, 4px 100%;
  background-repeat: repeat;
}

.cert-title {
  text-align: center;
  font-size: 19px;
  font-weight: 700;
  letter-spacing: 8px;
  margin: 12px 0 6px;
  color: #23201c;
}

.cert-no {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #6a6357;
  margin-bottom: 10px;
}

.cert-info {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: 10px;
}

.cert-info th,
.cert-info td {
  border: 1px solid #b9b3a5;
  padding: 4px 6px;
  text-align: left;
}

.cert-info th {
  width: 68px;
  background: #f3f0e6;
  color: #5d5648;
  font-weight: 600;
}

.cert-subtitle {
  font-size: 11px;
  color: #4527a0;
  font-weight: 600;
  margin-bottom: 5px;
}

.cert-data {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: 10px;
}

.cert-data th,
.cert-data td {
  border: 1px solid #b9b3a5;
  padding: 3px 6px;
  text-align: center;
}

.cert-data th {
  background: #f3f0e6;
  color: #5d5648;
}

.cert-empty {
  color: #9a9385;
  padding: 8px 0;
}

.cert-concl {
  font-size: 12px;
  margin-bottom: 4px;
}

.cert-concl-note {
  color: #7a7368;
  font-size: 11px;
}

/* 红色圆形印章（圆环 + 文字） */
.cert-stamp {
  position: absolute;
  right: 26px;
  bottom: 96px;
  width: 96px;
  height: 96px;
  border: 3px double #c62828;
  border-radius: 50%;
  color: #c62828;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  transform: rotate(-13deg);
  opacity: 0.86;
  background: rgba(198, 40, 40, 0.03);
}

.cert-stamp::before {
  content: '';
  position: absolute;
  inset: 6px;
  border: 1px solid rgba(198, 40, 40, 0.55);
  border-radius: 50%;
}

.cert-stamp-big {
  font-size: 21px;
  font-weight: 700;
  letter-spacing: 2px;
}

.cert-stamp-small {
  font-size: 9px;
  letter-spacing: 1px;
  margin-top: 2px;
}

.cert-stamp.is-none {
  border-color: #a9a39a;
  color: #a9a39a;
  opacity: 0.5;
}

.cert-stamp.is-none::before {
  border-color: rgba(169, 163, 154, 0.6);
}

/* 作废章：黑色方形感（沿用圆形但换灰黑色） */
.cert-stamp.is-void {
  border-color: #303030;
  color: #303030;
  opacity: 0.8;
}

.cert-stamp.is-void::before {
  border-color: rgba(48, 48, 48, 0.5);
}

.cert-sign {
  margin-top: auto;
  display: flex;
  gap: 10px;
  padding-right: 130px;
}

.cert-sign div {
  flex: 1;
  border-top: 1px solid #8d8577;
  padding-top: 3px;
  font-size: 11px;
}

.cert-sign span {
  display: block;
  color: #7a7368;
}

.cert-sign b {
  font-weight: 600;
}

.cert-foot {
  display: flex;
  justify-content: space-between;
  margin-top: 8px;
  padding-top: 6px;
  border-top: 1px dashed #b9b3a5;
  font-size: 10px;
  color: #7a7368;
}
</style>

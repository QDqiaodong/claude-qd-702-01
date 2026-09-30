import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一走 nginx 反代到后端 /api：
 *   成功 → 后端包成 { ok:true, message, data }，这里直接把 data 交给页面
 *   失败 → 后端包成 { ok:false, message:"中文业务提示" }，这里统一弹提示并 reject
 */
const http = axios.create({ baseURL: '/api', timeout: 15000 })

/** 演示环境的当前操作者；服务端仍会重新判定角色与指派关系，前端身份不能提权。 */
export function operatorHeaders() {
  try {
    const name = localStorage.getItem('operatorName')
    return name ? { 'X-Operator-Name': name } : {}
  } catch {
    return {}
  }
}

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body === 'object' && Object.prototype.hasOwnProperty.call(body, 'ok')) {
      if (body.ok) return body.data
      // 业务失败也把结构化信息抛给调用方（占用冲突需逐件标红并保留原选择）
      const err = new Error(body.message || '操作失败')
      err.biz = true
      err.conflicts = body.conflicts || null
      err.busyOrderNos = body.busyOrderNos || null
      if (!res.config || !res.config.hideError) ElMessage.error(body.message || '操作失败')
      return Promise.reject(err)
    }
    return body
  },
  (err) => {
    const body = err.response && err.response.data
    const message = (body && body.message) || err.message || '网络异常，请稍后再试'
    const bizErr = new Error(message)
    bizErr.biz = true
    bizErr.status = err.response && err.response.status
    bizErr.conflicts = (body && body.conflicts) || null
    bizErr.busyOrderNos = (body && body.busyOrderNos) || null
    if (!err.config || !err.config.hideError) ElMessage.error(message)
    return Promise.reject(bizErr)
  }
)

/** 模块一：送检客户 */
export const clientApi = {
  list: () => http.get('/clients'),
  orders: (id) => http.get(`/clients/${id}/orders`),
  create: (data) => http.post('/clients', data)
}

/** 模块二：计量器具台账（器具编号 = 自然主键） */
export const instrumentApi = {
  list: () => http.get('/instruments'),
  /** 【@NaturalId】按器具编号查器具 */
  find: (instNo) => http.get(`/instruments/${encodeURIComponent(instNo)}`),
  /** 【@NaturalId 批量加载】逗号分隔的多个器具编号 */
  batch: (instNos) => http.get('/instruments/batch', { params: { instNos } }),
  /** 派生查询：N 天内到期清单 */
  due: (days) => http.get('/instruments/due', { params: { days } }),
  create: (data) => http.post('/instruments', data),
  /** 台账「送检」：生成收样状态的委托单 */
  submit: (instNo, data) => http.post(`/instruments/${encodeURIComponent(instNo)}/submit`, data)
}

/** 模块三：检定规程 */
export const procedureApi = {
  list: () => http.get('/procedures'),
  create: (data) => http.post('/procedures', data)
}

/** 模块四：检定委托与流转单 */
export const orderApi = {
  list: (params) => http.get('/calibration-orders', { params: params || {} }),
  queue: () => http.get('/calibration-orders/queue'),
  overview: () => http.get('/calibration-orders/overview'),
  detail: (id) => http.get(`/calibration-orders/${id}`, { headers: operatorHeaders() }),
  create: (data) => http.post('/calibration-orders', data),
  result: (id, data) => http.post(`/calibration-orders/${id}/result`, data),
  advance: (id) => http.post(`/calibration-orders/${id}/advance`, null, { hideError: true }),
  rollback: (id) => http.post(`/calibration-orders/${id}/rollback`),
  void: (id) => http.post(`/calibration-orders/${id}/void`),
  deviceOptions: (id) => http.get(`/calibration-orders/${id}/device-options`),
  saveDevices: (id, deviceIds) =>
    http.put(`/calibration-orders/${id}/devices`, { deviceIds }, { hideError: true }),

  // ---- 结构化原始记录与版本留痕（操作者身份经请求头送服务端最终判定） ----
  createDraft: (id) => http.post(`/calibration-orders/${id}/records/draft`, null, { headers: operatorHeaders() }),
  saveDraft: (id, data) =>
    http.put(`/calibration-orders/${id}/records/draft`, data, { headers: operatorHeaders() }),
  submitRecord: (id, data) =>
    http.post(`/calibration-orders/${id}/records/submit`, data, { headers: operatorHeaders() }),
  approveRecord: (id) =>
    http.post(`/calibration-orders/${id}/records/approve`, null, { headers: operatorHeaders() }),
  returnRecord: (id, reason) =>
    http.post(`/calibration-orders/${id}/records/return`, { reason }, { headers: operatorHeaders() }),
  withdrawRecord: (id, reason) =>
    http.post(`/calibration-orders/${id}/records/withdraw`, reason ? { reason } : null, { headers: operatorHeaders() }),
  startRevision: (id, reason) =>
    http.post(`/calibration-orders/${id}/records/revision`, { reason }, { headers: operatorHeaders() }),
  assign: (id, data) =>
    http.put(`/calibration-orders/${id}/records/assignment`, data, { headers: operatorHeaders() }),
  migrateLegacy: (id, confirmedBy) =>
    http.post(`/calibration-orders/${id}/records/migrate-legacy`, { confirmedBy }, { headers: operatorHeaders() })
}

/** 模块五：标准器 */
export const deviceApi = {
  list: () => http.get('/standard-devices'),
  create: (data) => http.post('/standard-devices', data),
  setStatus: (id, status) => http.post(`/standard-devices/${id}/status`, { status }),
  reviseTrace: (id, data) => http.post(`/standard-devices/${id}/trace`, data)
}

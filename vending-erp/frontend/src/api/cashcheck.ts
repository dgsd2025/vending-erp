import request from '@/utils/request'
import { currentUserName } from '@/api/basedata'

function opHeaders(extra?: Record<string, string>) {
  return { headers: { 'X-User-Name': encodeURIComponent(currentUserName()), ...(extra || {}) } }
}

/**
 * M3-5 API:资金调整单(P1-7 钱盘差异唯一出口)+ 月度钱盘三核对(§8.2 D2)。
 * 铁律:钱只能被单据改——调整单确认(老板角色头)后由后端事件落 cash_flow,前端没有任何直改余额的接口。
 */

// ---------- 类型 ----------

/** 原因枚举(其他必备注;盘盈只能收/盘亏、手续费漏记只能支) */
export const ADJUST_REASONS = ['盘盈', '盘亏', '手续费漏记', '期初错', '其他'] as const

export interface AdjustRow {
  docId: number
  docNo: string
  docStatus: string
  bizDate: string
  confirmAt?: string | null
  remark?: string | null
  accountId: number
  accountName?: string
  direction: '收' | '支'
  amount: number | string
  reason: string
  cashCheckId?: number | null
}

export interface CheckItemRow {
  id: number
  itemType: '账户' | '平台' | '应付'
  refId: number
  refName?: string
  systemAmount: number | string
  actualAmount?: number | string | null
  diffAmount?: number | string | null
  adjustDocId?: number | null
  exitAction?: '补录' | '红冲' | null
  sourceDocId?: number | null
  note?: string | null
}

export interface CheckDetail {
  id: number
  checkNo: string
  checkPeriod: string
  checkStatus: '进行中' | '已完成' | '已作废'
  settleModeSnap: string
  platformSkipped?: boolean
  platformNote?: string | null
  remark?: string | null
  confirmAt?: string | null
  createTime?: string | null
  accountItems: CheckItemRow[]
  platformItems: CheckItemRow[]
  payableItems: CheckItemRow[]
}

export interface CheckListRow {
  id: number
  checkNo: string
  checkPeriod: string
  checkStatus: string
  platformSkipped?: boolean
  createTime?: string
  confirmAt?: string | null
  diffCount: number
}

export interface PayableExitResp {
  action: '补录' | '红冲'
  route?: string | null
  sourceDocId?: number | null
  message?: string
}

// ---------- 资金调整单 ----------

/** 建单并提交(adjustAmount 带符号:+实际多于系统收 / −实际少于系统支) */
export function createCashAdjust(data: {
  accountId: number
  adjustAmount: number
  reason: string
  remark?: string | null
  cashCheckId?: number | null
}): Promise<number> {
  return request.post('/v1/cash-adjust', data, opHeaders())
}

/** 老板确认 → DocStatusGuard 防双击 → 落 cash_flow → 单据完成 */
export function confirmCashAdjust(docId: number): Promise<void> {
  return request.post(
    `/v1/cash-adjust/${docId}/confirm`,
    {},
    opHeaders({ 'X-User-Role': encodeURIComponent('老板') }),
  )
}

export function listCashAdjusts(limit = 50): Promise<AdjustRow[]> {
  return request.get('/v1/cash-adjust', { params: { limit } })
}

// ---------- 钱盘三核对 ----------

export function startCashCheck(): Promise<number> {
  return request.post('/v1/cash-check', {}, opHeaders())
}

export function listCashChecks(limit = 12): Promise<CheckListRow[]> {
  return request.get('/v1/cash-check', { params: { limit } })
}

/** 当前进行中的核对(没有返回 null) */
export function currentCashCheck(): Promise<CheckDetail | null> {
  return request.get('/v1/cash-check/current')
}

export function getCashCheck(id: number): Promise<CheckDetail> {
  return request.get(`/v1/cash-check/${id}`)
}

/** 录实际数(手填;差异=实际−系统 后端落库) */
export function saveCashCheckActuals(
  id: number,
  rows: { itemId: number; actualAmount: number | null; note?: string | null }[],
): Promise<void> {
  return request.put(`/v1/cash-check/${id}/items`, { rows }, opHeaders())
}

/** 账户差异行 → 一键生成资金调整单(唯一出口,防重复) */
export function genAdjustFromCheck(id: number, itemId: number): Promise<number> {
  return request.post(`/v1/cash-check/${id}/items/${itemId}/adjust`, {}, opHeaders())
}

/** 应付不符出口两按钮:补录(返路由)/红冲(返来源单据id) */
export function markPayableExit(
  id: number,
  itemId: number,
  action: '补录' | '红冲',
): Promise<PayableExitResp> {
  return request.post(`/v1/cash-check/${id}/items/${itemId}/exit`, { action }, opHeaders())
}

export function finishCashCheck(id: number): Promise<void> {
  return request.post(`/v1/cash-check/${id}/finish`, {}, opHeaders())
}

export function cancelCashCheck(id: number): Promise<void> {
  return request.post(`/v1/cash-check/${id}/cancel`, {}, opHeaders())
}

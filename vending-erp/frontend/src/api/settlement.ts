import request from '@/utils/request'
import { currentUserName } from '@/api/basedata'

function opHeaders(extra?: Record<string, string>) {
  return { headers: { 'X-User-Name': encodeURIComponent(currentUserName()), ...(extra || {}) } }
}

/**
 * M3-3 API:平台结算双模式(附录D)。
 * PLATFORM=平台结算单(核销+两差对账+回填+两笔流水)/ DIRECT=商户账单核对单(只对差不动钱)/
 * UNSET=横幅+假设对比预览(不出正式数)。凭证走通用件 /v1/money/attachments(refType=settlement)。
 */

// ---------- 类型 ----------

export type SettleMode = 'UNSET' | 'PLATFORM' | 'DIRECT'

export interface HypothesisView {
  mode: SettleMode
  title: string
  explain: string
  amount: number | string
  informal: boolean
}

export interface SettlementOverview {
  mode: SettleMode
  banner?: string | null
  diffThreshold: number | string
  pendingBalance?: number | string | null
  pendingCount?: number | null
  pendingOldest?: string | null
  directNote?: string | null
  hypothesis: HypothesisView[]
}

export interface BillRow {
  id: number
  stmtNo: string
  modeSnap: SettleMode
  periodStart: string
  periodEnd: string
  platformAmount: number | string
  feeAmount: number | string
  actualAmount: number | string
  systemAmount: number | string
  diffSales: number | string
  diffArrival: number | string
  salesDiffOk?: boolean | null
  arrivalDiffOk?: boolean | null
  accountId?: number | null
  accountName?: string | null
  stlStatus: string
  confirmBy?: string | null
  confirmAt?: string | null
  diffNote?: string | null
  bookPeriod?: string | null
  /** 已传凭证数(前端软提示;后端确认仍有硬门禁) */
  attachmentCount?: number | null
}

export interface ConfirmResult {
  billId: number
  mode: SettleMode
  systemAmount: number | string
  diffSales: number | string
  diffArrival: number | string
  salesDiffOk: boolean
  arrivalDiffOk: boolean
  stlStatus: string
  backfillCount: number
  flowCount: number
}

export interface ExchangeRoi {
  exchangeCost: number | string
  exchangeQty: number | string
  costMissingCount: number
  subsidyUsed: number | string
  subsidyPending: number | string
  subsidyConfirmed: number | string
  net: number | string
}

export interface BillCreateReq {
  periodStart: string
  periodEnd: string
  platformAmount: number
  feeAmount?: number | null
  actualAmount?: number | null
  accountId?: number | null
  remark?: string
}

// ---------- 接口 ----------

export function settlementOverview(): Promise<SettlementOverview> {
  return request.get('/v1/settlement/overview')
}

export function listSettlementBills(status?: string): Promise<BillRow[]> {
  return request.get('/v1/settlement/bills', { params: { status } })
}

export function createSettlementBill(req: BillCreateReq): Promise<number> {
  return request.post('/v1/settlement/bills', req, opHeaders())
}

export function confirmSettlementBill(id: number): Promise<ConfirmResult> {
  return request.post(`/v1/settlement/bills/${id}/confirm`, null, opHeaders())
}

export function resolveSettlementDiff(id: number, note: string): Promise<void> {
  return request.post(`/v1/settlement/bills/${id}/resolve-diff`, { note }, opHeaders())
}

export function voidSettlementBill(id: number): Promise<void> {
  return request.post(`/v1/settlement/bills/${id}/void`, null, opHeaders())
}

export function exchangeRoi(from?: string, to?: string): Promise<ExchangeRoi> {
  return request.get('/v1/settlement/exchange-roi', { params: { from, to } })
}

import request from '@/utils/request'
import { currentUserName } from '@/api/basedata'

function opHeaders(extra?: Record<string, string>) {
  return { headers: { 'X-User-Name': encodeURIComponent(currentUserName()), ...(extra || {}) } }
}

/** 老板角色头(占位,与解锁/锁账红冲同一把尺子;SSO 后替换) */
function bossHeaders() {
  return opHeaders({ 'X-User-Role': encodeURIComponent('老板') })
}

/**
 * 应付供应商全链 API(M3-2,§9.2 业财一体样板流程):
 * 供应商往来卡/对账单(p8)· 结算单(自动生成,无新建接口)· 付款单(凭证硬门禁)· 抵扣确认单。
 */

// ---------- 类型 ----------

export interface SupplierOverviewRow {
  supplierId: number
  supplierCode: string
  supplierName: string
  contact?: string | null
  settleMethod: string
  accountDays?: number | null
  coopStatus: string
  openingPayable: number | string
  purchaseTotal: number | string
  returnTotal: number | string
  deductionTotal: number | string
  paymentTotal: number | string
  balance: number | string
  prepay: boolean
  overdue: boolean
  overdueDays?: number | null
  pendingDeductions: number
  pendingRedBills: number
}

export interface BillRow {
  id: number
  billNo: string
  direction: '正常' | '红字'
  supplierId: number
  supplierName?: string | null
  sourceDocId?: number | null
  sourceDocNo?: string | null
  amountDue: number | string
  deductionAmount: number | string
  amountActual: number | string
  billStatus: string
  diffNote?: string | null
  offsetIntoBillId?: number | null
  dueDate?: string | null
  createTime?: string | null
}

export interface PaymentRow {
  id: number
  payNo: string
  supplierId: number
  supplierName?: string | null
  accountId: number
  accountName?: string | null
  amount: number | string
  deductionAmount: number | string
  settleBillId?: number | null
  billNo?: string | null
  payStatus: string
  payTime?: string | null
  remark?: string | null
  /** 非空=本行是负额红冲行(指向原付款单) */
  redFlushOf?: number | null
  createTime?: string | null
}

export interface DeductionRow {
  id: number
  dedNo: string
  supplierId: number
  dedSource: string
  amount: number | string
  dedStatus: string
  usedSettleBillId?: number | null
  periodDesc?: string | null
  createTime?: string | null
}

export interface StatementResp {
  supplierId: number
  supplierName: string
  opening: number | string
  closing: number | string
  lines: {
    bizDate: string
    refNo: string
    lineType: string
    amount: number | string
    balance: number | string
    remark?: string | null
    accountName?: string | null
    billNo?: string | null
  }[]
}

export interface BillConfirmResult {
  billId: number
  amountDue: number | string
  deductionAmount: number | string
  redOffsetAmount: number | string
  amountActual: number | string
}

// ---------- 供应商往来 ----------

export function supplierOverview(): Promise<SupplierOverviewRow[]> {
  return request.get('/v1/settle/suppliers/overview')
}

export function supplierStatement(supplierId: number): Promise<StatementResp> {
  return request.get(`/v1/settle/suppliers/${supplierId}/statement`)
}

/** 对账单 xlsx 下载地址(a 标签直开) */
export function statementExportUrl(supplierId: number): string {
  return `/api/v1/settle/suppliers/${supplierId}/statement/export`
}

// ---------- 结算单 ----------

export function listBills(params: { supplierId?: number; status?: string }): Promise<BillRow[]> {
  return request.get('/v1/settle/bills', { params })
}

/** 老板复核确认(§9.2 确认点②):勾选带入同供应商待抵扣;在挂红字自动冲抵 */
export function confirmBill(id: number, deductionIds: number[]): Promise<BillConfirmResult> {
  return request.post(`/v1/settle/bills/${id}/confirm`, { deductionIds }, bossHeaders())
}

// ---------- 付款单 ----------

export function listPayments(supplierId?: number): Promise<PaymentRow[]> {
  return request.get('/v1/settle/payments', { params: { supplierId } })
}

export function createPayment(data: {
  supplierId: number
  accountId: number
  amount: number
  settleBillId?: number | null
  remark?: string
}): Promise<number> {
  return request.post('/v1/settle/payments', data, opHeaders())
}

/** 确认付款:后端凭证硬门禁——没传转账截图直接拒绝 */
export function confirmPayment(id: number): Promise<PaymentRow> {
  return request.post(`/v1/settle/payments/${id}/confirm`, null, opHeaders())
}

export function resolvePaymentDiff(id: number, note: string): Promise<PaymentRow> {
  return request.post(`/v1/settle/payments/${id}/resolve-diff`, { note }, opHeaders())
}

/** 作废付款单(M3-9 逆向出口:仅待付款,钱没动;备注强制留痕) */
export function voidPayment(id: number, note: string): Promise<void> {
  return request.post(`/v1/settle/payments/${id}/void`, { note }, opHeaders())
}

/** 红冲付款单(钱已动的唯一逆向):负额红冲行+退款流水+结算单回待付款;备注强制 */
export function redFlushPayment(id: number, note: string): Promise<number> {
  return request.post(`/v1/settle/payments/${id}/red-flush`, { note }, opHeaders())
}

/** 应付逾期 aging(驾驶舱红灯只读数据口) */
export interface PayableAgingResp {
  overdueCount: number
  maxOverdueDays: number
  rows: { supplierId: number; supplierName: string; balance: number | string; overdueDays: number | null }[]
}

export function payableAging(): Promise<PayableAgingResp> {
  return request.get('/v1/settle/payable-aging')
}

// ---------- 抵扣确认单 ----------

export function listDeductions(params: { supplierId?: number; status?: string }): Promise<DeductionRow[]> {
  return request.get('/v1/settle/deductions', { params })
}

export function createDeduction(data: {
  supplierId: number
  dedSource: '兑换' | '厂家补贴'
  amount: number
  periodDesc?: string
}): Promise<number> {
  return request.post('/v1/settle/deductions', data, opHeaders())
}

export function voidDeduction(id: number): Promise<void> {
  return request.put(`/v1/settle/deductions/${id}/void`, null, opHeaders())
}

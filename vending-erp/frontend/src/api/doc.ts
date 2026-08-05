import request from '@/utils/request'
import { currentUserName } from '@/api/basedata'

/** 写操作统一带经手人头(SSO 前占位,与 basedata/purchase 同一套) */
function opHeaders(extra?: Record<string, string>) {
  return { headers: { 'X-User-Name': encodeURIComponent(currentUserName()), ...(extra || {}) } }
}

/**
 * 单据通用 API(M1-7):红冲影响清单/执行 + 成本调整预览/执行。
 * 铁律:红冲/成本调整必过确认对话框(影响清单),前端展示 preview 后才可执行。
 */

// ---------- 类型 ----------

export interface DocHead {
  id: number
  docNo: string
  docType: string
  docStatus: string
  bizDate: string
  machineId?: number | null
  supplierId?: number | null
  purchaseOrderId?: number | null
  redFlushOf?: number | null
  negStockExempt?: boolean
  totalQty?: number | string
  totalAmount?: number | string
  bookPeriod?: string | null
  plLine?: string | null
  remark?: string | null
}

export interface DocItemRow {
  id: number
  docId: number
  productId: number
  qty: number | string
  expectQty?: number | string | null
  unitPrice?: number | string | null
  amount?: number | string | null
  poItemId?: number | null
  remark?: string | null
}

export interface DocDetail {
  head: DocHead
  items: DocItemRow[]
}

/** 红冲影响清单(确认页数据) */
export interface RedFlushPreview {
  originDocId: number
  originDocNo: string
  docType: string
  docStatus: string
  totalAmount?: number | string
  blockers: string[]
  executable: boolean
  bookPeriod: string
  lockedPeriod: boolean
  lockLine?: string | null
  stockChanges: {
    productId: number
    skuCode?: string
    productName?: string
    warehouseDelta: number | string
    machineDelta: number | string
    warehouseNow: number | string
    warehouseAfter: number | string
  }[]
  payableImpact?: { enabled: boolean; note: string; wouldReverseAmount: number | string } | null
  marginImpacts: { period: string; locationType: string; costAmountReversal: number | string }[]
  marginNote?: string
}

export interface CostAdjustLine {
  docItemId: number
  correctPrice: number
}

/** 成本调整预览(未售/已售切分) */
export interface CostAdjustPreview {
  originDocId: number
  originDocNo: string
  lines: {
    docItemId: number
    productId: number
    skuCode?: string
    productName?: string
    qty: number | string
    wrongPrice: number | string
    correctPrice: number | string
    deltaPerUnit: number | string
    unsoldQty: number | string
    soldQty: number | string
    unsoldAdjustAmount: number | string
    soldAdjustAmount: number | string
  }[]
  unsoldAdjustTotal: number | string
  soldAdjustTotal: number | string
  note?: string
}

// ---------- 接口 ----------

export function getDocDetail(id: number): Promise<DocDetail> {
  return request.get(`/v1/docs/${id}`)
}

/** 红冲影响清单(必过确认页,P0-1) */
export function redFlushPreview(id: number): Promise<RedFlushPreview> {
  return request.get(`/v1/docs/${id}/red-flush/preview`)
}

/** 执行整单红冲:reason 强制;锁账期需 bossOverride=true */
export function redFlushExecute(id: number, reason: string, bossOverride: boolean): Promise<number> {
  return request.post(`/v1/docs/${id}/red-flush`, { reason, bossOverride }, opHeaders())
}

/** 成本调整预览:选错误行+正确单价 → 未售/已售切分 */
export function costAdjustPreview(id: number, lines: CostAdjustLine[]): Promise<CostAdjustPreview> {
  return request.post(`/v1/docs/${id}/cost-adjust/preview`, { lines })
}

/** 执行成本调整:生成 doc_type=成本调整 单并确认过账 */
export function costAdjustExecute(id: number, lines: CostAdjustLine[], remark?: string): Promise<number> {
  return request.post(`/v1/docs/${id}/cost-adjust`, { lines, remark: remark || null }, opHeaders())
}

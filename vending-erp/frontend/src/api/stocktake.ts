import request from '@/utils/request'
import { currentUserName } from '@/api/basedata'

function opHeaders(extra?: Record<string, string>) {
  return { headers: { 'X-User-Name': encodeURIComponent(currentUserName()), ...(extra || {}) } }
}

/**
 * 盘点 API(M2-4,对照 mockup p9):
 * 创建(选范围→系统快照账面)→ 录实盘(只录差异,未录=视同相符)→ 提交 →
 * 五步向导(第1步系统查账 / 第3步确认生成盘盈亏单+机器锚点)→ 损耗统计。
 */

// ---------- 类型 ----------

/** 差异原因六枚举(差异行必选) */
export const DIFF_REASONS = ['吞货掉货', '过期报损', '录入错误', '被盗', '盘点错误', '原因不明'] as const

/** 账错类原因:机器盘点只校锚点不开财务单 */
export const ACCOUNT_ERROR_REASONS = ['录入错误', '盘点错误']

export interface StocktakeListRow {
  id: number
  stNo: string
  scopeType: '仓库' | '机器'
  machineId?: number | null
  machineName?: string | null
  snapshotTime: string
  stStatus: '进行中' | '待确认' | '已完成' | '已作废'
  gainDocId?: number | null
  lossDocId?: number | null
  sourceTask?: string | null
  diffCount: number
  diffQty?: number | string | null
  diffAmount?: number | string | null
}

export interface StocktakeItemRow {
  id: number
  productId: number
  skuCode?: string
  productName?: string
  slotNo?: string | null
  bookQty: number
  actualQty: number
  diffQty: number
  diffAmount?: number | null
  diffReason?: string | null
  offlineExempt?: boolean | null
}

export interface StocktakeDetail {
  id: number
  stNo: string
  scopeType: '仓库' | '机器'
  machineId?: number | null
  machineName?: string | null
  snapshotTime: string
  stStatus: string
  gainDocId?: number | null
  lossDocId?: number | null
  sourceTask?: string | null
  items: StocktakeItemRow[]
  diffCount: number
}

export interface PrecheckResp {
  imports7d: { id: number; batchNo: string; fileType: string; batchStatus: string; periodRange?: string; rowOk?: number; createTime: string }[]
  docs7d: { id: number; docNo: string; docType: string; docStatus: string; docSource?: string; bizDate: string; totalQty?: number }[]
  offlineSales: { productId: number; productName?: string; cnt: number; qty: number }[]
  hints: string[]
}

export interface ConfirmResp {
  stocktakeId: number
  gainDocId?: number | null
  lossDocId?: number | null
  returnDocId?: number | null
  anchorCount: number
  skippedAnchorProducts: number[]
  lossAmount?: number | null
  gainAmount?: number | null
}

export interface LossStatRow {
  month: string
  reason: string
  itemCount: number
  qty: number
  amount: number
}

// ---------- 接口 ----------

export function createStocktake(data: { scopeType: string; machineId?: number | null; sourceTask?: string }): Promise<number> {
  return request.post('/v1/stocktake', data, opHeaders())
}

export function listStocktakes(limit = 50): Promise<StocktakeListRow[]> {
  return request.get('/v1/stocktake', { params: { limit } })
}

export function getStocktake(id: number): Promise<StocktakeDetail> {
  return request.get(`/v1/stocktake/${id}`)
}

/** 只提交差异行(整包替换,未提交=视同相符) */
export function saveStocktakeItems(
  id: number,
  rows: { productId: number; actualQty: number; diffReason?: string | null; offlineExempt?: boolean }[],
): Promise<void> {
  return request.put(`/v1/stocktake/${id}/items`, { rows }, opHeaders())
}

export function submitStocktake(id: number): Promise<void> {
  return request.post(`/v1/stocktake/${id}/submit`, {}, opHeaders())
}

/** 五步第1步:系统自动查账 */
export function precheckStocktake(id: number): Promise<PrecheckResp> {
  return request.get(`/v1/stocktake/${id}/precheck`)
}

/** 确认(第3步):>¥50 需老板角色头;锁账期需 bossOverride+note */
export function confirmStocktake(
  id: number,
  opts: { asBoss?: boolean; bossOverride?: boolean; note?: string },
): Promise<ConfirmResp> {
  const headers: Record<string, string> = {}
  if (opts.asBoss) headers['X-User-Role'] = encodeURIComponent('老板')
  return request.post(
    `/v1/stocktake/${id}/confirm`,
    { bossOverride: !!opts.bossOverride, note: opts.note || null },
    opHeaders(headers),
  )
}

export function cancelStocktake(id: number): Promise<void> {
  return request.post(`/v1/stocktake/${id}/cancel`, {}, opHeaders())
}

/** 损耗统计:按原因×月份(件数/成本额) */
export function getLossStats(months = 6): Promise<LossStatRow[]> {
  return request.get('/v1/stocktake/loss-stats', { params: { months } })
}

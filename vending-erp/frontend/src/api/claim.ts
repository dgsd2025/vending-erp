import request from '@/utils/request'
import { currentUserName } from '@/api/basedata'

function opHeaders(extra?: Record<string, string>) {
  return { headers: { 'X-User-Name': encodeURIComponent(currentUserName()), ...(extra || {}) } }
}

/**
 * 索赔单 API(M3-4,§9.3 场景4):
 * 盘亏归因(吞货/被盗)→ 发起申请 → 申请中(计索赔应收)
 *   →(赔付凭证必传)到账登记(落流水 其他收入-赔付)/ 放弃(备注必填)。
 * 凭证上传复用 /v1/money/attachments(refType=claim)。
 */

// ---------- 类型 ----------

export const CLAIM_TARGETS = ['厂家', '平台'] as const
export const CLAIM_STATUS = ['申请中', '已到账', '放弃'] as const
/** 可索赔的盘亏归因(与后端 ClaimService 一致) */
export const CLAIMABLE_REASONS = ['吞货掉货', '被盗'] as const

export interface ClaimRow {
  id: number
  claimNo: string
  sourceType: string
  sourceId?: number | null
  claimTarget: string
  amount: number | string
  claimStatus: '申请中' | '已到账' | '放弃'
  receivedAmount?: number | string | null
  receivedTime?: string | null
  cashFlowId?: number | null
  remark?: string | null
  createTime?: string
  attachmentCount: number
}

export interface NetShrinkageResp {
  lossAmount: number | string
  compensatedAmount: number | string
  netAmount: number | string
  fromPeriod?: string | null
  toPeriod?: string | null
}

// ---------- 接口 ----------

export function listClaims(status?: string): Promise<ClaimRow[]> {
  return request.get('/v1/claims', { params: { status } })
}

export function getClaim(id: number): Promise<ClaimRow> {
  return request.get(`/v1/claims/${id}`)
}

export function createClaim(data: {
  claimTarget: string
  amount: number
  sourceId?: number | null
  stocktakeItemIds?: number[]
  remark?: string
}): Promise<number> {
  return request.post('/v1/claims', data, opHeaders())
}

/** 到账登记:先传赔付凭证(refType=claim)否则后端硬拒 */
export function receiveClaim(
  id: number,
  data: { accountId: number; receivedAmount?: number | null; receivedTime?: string | null },
): Promise<ClaimRow> {
  return request.post(`/v1/claims/${id}/receive`, data, opHeaders())
}

export function abandonClaim(id: number, remark: string): Promise<void> {
  return request.post(`/v1/claims/${id}/abandon`, { remark }, opHeaders())
}

/** 索赔应收 = Σ申请中金额(资产快照第4项取数口) */
export function claimReceivable(): Promise<number> {
  return request.get('/v1/claims/receivable')
}

/** 净损耗 = 损耗 − 已获赔(区间可空=全期,入账月 yyyy-MM) */
export function netShrinkage(params?: {
  fromPeriod?: string
  toPeriod?: string
}): Promise<NetShrinkageResp> {
  return request.get('/v1/claims/net-shrinkage', { params })
}

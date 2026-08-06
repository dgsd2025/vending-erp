import request from '@/utils/request'
import { currentUserName } from '@/api/basedata'

/** 写操作统一带经手人头(SSO 前占位) */
function opHeaders() {
  return { headers: { 'X-User-Name': encodeURIComponent(currentUserName()) } }
}

/**
 * AI 补货 API(M2-1/M2-2):规则引擎出数字,LLM 只出解释。
 * 参数读写复用 basedata.ts 的 replenish-configs 接口。
 */

// ---------- 类型 ----------

/** 建议行(机器侧/仓库侧共用;formulaJson = 🔬 公式与全部输入值快照) */
export interface PlanRow {
  id: number
  planDate: string
  planType: string
  machineId: number | null
  machineName: string | null
  productId: number
  skuCode: string
  productName: string
  unit: string
  boxSpec: number | string | null
  shelfLifeDays: number | null
  currentQty: number | string
  avgDaily: number | string | null
  sigmaDaily: number | string | null
  targetLevelS: number | string | null
  safetyStock: number | string | null
  suggestQty: number | string
  boxRoundQty: number | string | null
  planStatus: string
  aiExplain: string | null
  llmCallId: number | null
  formulaJson: string | null
}

export interface SuggestionsResp {
  planDate: string | null
  dataAsOf: string | null
  staleDays: number | null
  rows: PlanRow[]
}

export interface RecalcResp {
  planDate: string
  dataAsOf: string
  staleDays: number
  machineRows: number
  purchaseRows: number
}

/** 🔬 过程详情:plan 全量 + llm 透明四件套 */
export interface PlanDetailResp {
  plan: PlanRow & { formulaJson: string }
  llm?: {
    model: string
    reasoning: string
    outputText: string
    confidence: number | string
    inputDigest: string
    createTime: string
    callStatus: string
  }
}

// ---------- 接口 ----------

export function machineSuggestions(planDate?: string): Promise<SuggestionsResp> {
  return request.get('/v1/replenish/machine-suggestions', { params: { planDate } })
}

export function purchaseSuggestions(planDate?: string): Promise<SuggestionsResp> {
  return request.get('/v1/replenish/purchase-suggestions', { params: { planDate } })
}

export function recalc(): Promise<RecalcResp> {
  return request.post('/v1/replenish/recalc', null, opHeaders())
}

export function ignorePlan(id: number): Promise<void> {
  return request.post(`/v1/replenish/plans/${id}/ignore`, null, opHeaders())
}

export function restorePlan(id: number): Promise<void> {
  return request.post(`/v1/replenish/plans/${id}/restore`, null, opHeaders())
}

export function adoptPlan(id: number): Promise<void> {
  return request.post(`/v1/replenish/plans/${id}/adopt`, null, opHeaders())
}

export function planDetail(id: number): Promise<PlanDetailResp> {
  return request.get(`/v1/replenish/plans/${id}`)
}

import request from '@/utils/request'
import { currentUserName } from '@/api/basedata'

function opHeaders() {
  return { headers: { 'X-User-Name': encodeURIComponent(currentUserName()) } }
}

/**
 * PDCA 改进循环 API(M4-3,对照 mockup p11 / §8.4):
 * 六环节看板(C 指标红绿灯)+ 改进任务 CRUD + 到期自动回查三分支 + 盘亏向导第 5 步一键起草。
 * 验证指标 = 结构化三件套(指标键 + 目标值 + 比较方向),到期取指标当前值 vs 目标自动判定。
 */

// ---------- 常量 ----------
export const SCENES = ['补货', '采购', '选品', '定价', '钱账', '盘点'] as const
export const ITEM_STATUSES = ['进行中', '验证通过', '未见效升级', '已关闭'] as const
export const COMPARE_OPS = [
  { value: '<=', label: '≤ 降到目标以下算通过(损耗/带回率/缺货这类越低越好)' },
  { value: '>=', label: '≥ 升到目标以上算通过(动销率/符合率这类越高越好)' },
] as const

// ---------- 类型 ----------
export interface MetricDef {
  key: string
  name: string
  unit: string
  paramHint: string | null
  defaultOp: string
}

export interface MetricValue {
  key: string
  param: string | null
  value: number | null
  unit: string | null
  note: string
}

export interface ItemRow {
  id: number
  sourceScene: string
  problemDesc: string
  measure: string
  ownerUser?: number | null
  verifyMetric: string
  metricKey?: string | null
  metricName?: string | null
  metricParam?: string | null
  targetValue?: number | null
  compareOp: string
  baselineValue?: number | null
  verifyValue?: number | null
  verifyResult?: string | null
  verifyNote?: string | null
  verifiedAt?: string | null
  verifyDate: string
  itemStatus: string
  sourceRefType?: string | null
  sourceRefId?: number | null
  aiDraft?: boolean
  createTime?: string | null
  due: boolean
  dueThisWeek: boolean
}

export interface ItemSaveReq {
  sourceScene: string
  problemDesc: string
  measure: string
  ownerUser?: number | null
  verifyMetric?: string | null
  metricKey?: string | null
  metricParam?: string | null
  targetValue?: number | null
  compareOp?: string
  verifyDate: string
  sourceRefType?: string | null
  sourceRefId?: number | null
  aiDraft?: boolean
}

export interface RecheckResp {
  itemId: number
  result: '通过' | '未达' | '需人工判定'
  currentValue?: number | null
  targetValue?: number | null
  compareOp: string
  itemStatus: string
  note: string
}

export interface RecheckBatchResp {
  total: number
  passed: number
  failed: number
  manual: number
  details: RecheckResp[]
}

export interface BoardIndicator {
  label: string
  display: string
  level: 'green' | 'amber' | 'red' | 'gray'
}

export interface BoardScene {
  scene: string
  icon: string
  target: string
  indicators: BoardIndicator[]
  light: 'green' | 'amber' | 'red' | 'gray'
  openItems: number
  hint: string
}

export interface BoardResp {
  period: string
  scenes: BoardScene[]
  dueCount: number
}

export interface DraftResp {
  stocktakeId: number
  stNo: string
  drafts: ItemSaveReq[]
  existing: ItemRow[]
}

// ---------- API ----------
export const pdcaApi = {
  board(): Promise<BoardResp> {
    return request.get('/v1/pdca/board')
  },
  items(params?: { status?: string; scene?: string; refType?: string; refId?: number }): Promise<ItemRow[]> {
    return request.get('/v1/pdca/items', { params })
  },
  dueWeek(): Promise<ItemRow[]> {
    return request.get('/v1/pdca/items/due-week')
  },
  create(req: ItemSaveReq): Promise<number> {
    return request.post('/v1/pdca/items', req, opHeaders())
  },
  update(id: number, req: ItemSaveReq): Promise<void> {
    return request.put(`/v1/pdca/items/${id}`, req, opHeaders())
  },
  close(id: number, note: string): Promise<void> {
    return request.post(`/v1/pdca/items/${id}/close`, { note }, opHeaders())
  },
  recheck(id: number): Promise<RecheckResp> {
    return request.post(`/v1/pdca/items/${id}/recheck`, {}, opHeaders())
  },
  recheckDue(): Promise<RecheckBatchResp> {
    return request.post('/v1/pdca/items/recheck-due', {}, opHeaders())
  },
  metrics(): Promise<MetricDef[]> {
    return request.get('/v1/pdca/metrics')
  },
  metricValue(key: string, param?: string): Promise<MetricValue> {
    return request.get('/v1/pdca/metrics/value', { params: { key, param } })
  },
  draftFromStocktake(stocktakeId: number): Promise<DraftResp> {
    return request.get(`/v1/pdca/draft-from-stocktake/${stocktakeId}`)
  },
}

import request from '@/utils/request'
import { currentUserName } from '@/api/basedata'

function opHeaders(extra?: Record<string, string>) {
  return { headers: { 'X-User-Name': encodeURIComponent(currentUserName()), ...(extra || {}) } }
}

/**
 * 支出单/设备台账/线下收入复合单 API(M3-4,§9.3 场景7/8 · P2-13):
 * - 支出单:录入(待确认)→ 传凭证(refType=expense,硬门禁)→ 确认落流水(杂费行);
 * - 设备购置确认时同步建台账行(购入价/日期/折余=购入价,展示用);
 * - 线下复合单:一次录入同事务生成 sale_record(线下补录,不入待结算)+ cash_flow(其他收入-平台外)+ 豁免标记。
 */

// ---------- 类型 ----------

export const EXPENSE_CATEGORIES = ['电费', '维修', '杂支', '设备购置'] as const
export const EQUIPMENT_STATUS = ['在用', '报废', '出售'] as const

export interface ExpenseRow {
  id: number
  expNo: string
  category: string
  amount: number | string
  accountId: number
  accountName?: string | null
  isEquipment?: boolean
  equipmentId?: number | null
  equipName?: string | null
  expStatus: '待确认' | '已完成'
  bizDate: string
  bookPeriod?: string | null
  remark?: string | null
  createTime?: string
  attachmentCount: number
}

export interface EquipmentRow {
  id: number
  equipName: string
  machineId?: number | null
  buyPrice: number | string
  buyDate?: string | null
  residualValue?: number | string | null
  equipStatus: string
  expenseId?: number | null
  createTime?: string
}

export interface OfflineSaleResp {
  saleRecordId: number
  orderNo: string
  cashFlowId: number
  exemptHint: string
}

export interface OfflineSaleRow {
  saleRecordId: number
  orderNo: string
  machineId: number
  productId: number
  qty: number | string
  amount: number | string
  bizTime: string
}

// ---------- 支出单 ----------

export function listExpenses(status?: string): Promise<ExpenseRow[]> {
  return request.get('/v1/expenses', { params: { status } })
}

export function createExpense(data: {
  category: string
  amount: number
  accountId: number
  bizDate?: string
  equipName?: string
  remark?: string
}): Promise<number> {
  return request.post('/v1/expenses', data, opHeaders())
}

/** 确认落流水:先传凭证(refType=expense)否则后端硬拒 */
export function confirmExpense(id: number): Promise<ExpenseRow> {
  return request.post(`/v1/expenses/${id}/confirm`, {}, opHeaders())
}

// ---------- 设备台账 ----------

export function listEquipment(): Promise<EquipmentRow[]> {
  return request.get('/v1/equipment')
}

export function updateEquipment(
  id: number,
  data: { equipName: string; machineId?: number | null; residualValue?: number | null; equipStatus: string },
): Promise<void> {
  return request.put(`/v1/equipment/${id}`, data, opHeaders())
}

// ---------- 线下收入复合单 ----------

export function createOfflineSale(data: {
  machineId: number
  productId: number
  qty: number
  amount: number
  accountId: number
  bizTime?: string
  remark?: string
}): Promise<OfflineSaleResp> {
  return request.post('/v1/offline-sales', data, opHeaders())
}

export function listOfflineSales(limit = 20): Promise<OfflineSaleRow[]> {
  return request.get('/v1/offline-sales', { params: { limit } })
}

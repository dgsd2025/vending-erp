import request from '@/utils/request'
import { currentUserName } from '@/api/basedata'

/** 写操作统一带经手人头(SSO 前占位) */
function opHeaders() {
  return { headers: { 'X-User-Name': encodeURIComponent(currentUserName()) } }
}

/**
 * Pre-kit 配货单 + 出库上架页(p5)API(M2-3):
 * 补货建议勾选行 → 按机分组配货单 → 装箱执行 → 次日导入自动核销(带回率)。
 */

// ---------- 类型 ----------

/** 配货单列表行(带机器名/行数/合计 + 超窗黄灯) */
export interface PrekitTicketRow {
  id: number
  ticketNo: string
  machineId: number
  machineName: string | null
  planDate: string
  ticketStatus: string
  verifyDocId: number | null
  takebackRate: number | string | null
  execAt: string | null
  verifyAt: string | null
  createTime: string
  itemCount: number
  totalPlanned: number | string
  /** 超窗黄灯:未核销且过了 ±48h 核销窗口 */
  overdue: boolean
}

/** 配货单明细行(FEFO 提示级:仓库最早入库日) */
export interface PrekitItemRow {
  id: number
  ticketId: number
  productId: number
  planId: number | null
  skuCode: string
  productName: string
  unit: string
  barcode: string | null
  qtyPlanned: number | string
  qtyLoaded: number | string | null
  qtyTakeback: number | string | null
  fefoEarliestInDate: string | null
}

export interface PrekitTicketDetail {
  ticket: {
    id: number
    ticketNo: string
    machineId: number
    planDate: string
    ticketStatus: string
    verifyDocId: number | null
    takebackRate: number | string | null
    execAt: string | null
    verifyAt: string | null
  }
  head: PrekitTicketRow | null
  items: PrekitItemRow[]
}

export interface GenerateResp {
  ticketCount: number
  itemCount: number
  ticketIds: number[]
}

/** 转移单列表行(出库上架/退库) */
export interface TransferRow {
  id: number
  docNo: string
  docType: string
  docStatus: string
  docSource: string
  bizDate: string
  machineId: number | null
  machineName: string | null
  totalQty: number | string
  matchedDocId: number | null
  importBatchId: number | null
  confirmAt: string | null
  remark: string | null
  itemCount: number
}

export interface ManualTransferItem {
  productId: number
  qty: number
  slotNo?: string
}

// ---------- 接口 ----------

/** 生成配货单(p2 勾选机器侧建议行;qty 空=建议量) */
export function generateTickets(items: { planId: number; qty?: number }[]): Promise<GenerateResp> {
  return request.post('/v1/prekit/tickets/generate', { items }, opHeaders())
}

export function listTickets(status?: string): Promise<PrekitTicketRow[]> {
  return request.get('/v1/prekit/tickets', { params: { status } })
}

export function ticketDetail(id: number): Promise<PrekitTicketDetail> {
  return request.get(`/v1/prekit/tickets/${id}`)
}

/** 标记已执行(仓库装箱出发) */
export function executeTicket(id: number): Promise<void> {
  return request.post(`/v1/prekit/tickets/${id}/execute`, null, opHeaders())
}

/** 手动核销(超窗补导后扫窗口重算) */
export function verifyTicket(id: number): Promise<{ matched: boolean; message?: string }> {
  return request.post(`/v1/prekit/tickets/${id}/verify`, null, opHeaders())
}

/** 改带出量(仅「已生成」) */
export function updateItemQty(itemId: number, qtyPlanned: number): Promise<void> {
  return request.put(`/v1/prekit/items/${itemId}`, { qtyPlanned }, opHeaders())
}

/** 转移单列表(p5 转移单卡) */
export function listTransfers(): Promise<TransferRow[]> {
  return request.get('/v1/prekit/transfers')
}

/** 手工转移单录入(兜底;出库上架确认后=预挂单) */
export function createManualTransfer(req: {
  docType: string
  machineId: number
  bizDate: string
  remark?: string
  items: ManualTransferItem[]
}): Promise<{ docId: number; docNo: string; docStatus: string }> {
  return request.post('/v1/prekit/transfers', req, opHeaders())
}

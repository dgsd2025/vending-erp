import request from '@/utils/request'
import { currentUserName, type PageResult } from '@/api/basedata'

/** 写操作统一带经手人头(SSO 前占位,与 basedata 同一套) */
function opHeaders() {
  return { headers: { 'X-User-Name': encodeURIComponent(currentUserName()) } }
}

/**
 * 采购模块 API(M1-4):轻量订货单(P0-5)/ 收货入库(doc 承载)/ 在途 / 价格本比价。
 * 统一 R{code,message,data},request 已解包出 data。
 */

// ---------- 类型 ----------

export interface PurchaseOrder {
  id?: number
  poNo?: string
  supplierId: number
  expectDate?: string | null
  poStatus?: string
  totalAmount?: number | string
  remark?: string | null
  createTime?: string
}

/** 列表/详情 VO:订货单 + 汇总 + 超期黄灯 */
export interface PoVo {
  po: PurchaseOrder
  itemCount: number
  qtyOrdered: number | string
  qtyReceived: number | string
  inTransitQty: number | string
  overdue: boolean
}

export interface PoItemRow {
  id: number
  poId: number
  productId: number
  qtyOrdered: number | string
  qtyReceived: number | string
  unitPrice: number | string
  amount: number | string
  skuCode?: string
  productName?: string
  boxSpec?: number | string | null
  unit?: string
}

export interface PoCreateReq {
  supplierId: number
  expectDate?: string | null
  remark?: string | null
  items: { productId: number; qtyOrdered: number; unitPrice?: number | null }[]
}

export interface ReceiptItemReq {
  productId: number
  qty: number
  expectQty?: number | null
  unitPrice: number
  poItemId?: number | null
  remark?: string | null
}

export interface ReceiptCreateReq {
  supplierId: number
  bizDate: string
  purchaseOrderId?: number | null
  remark?: string | null
  items: ReceiptItemReq[]
}

export interface ReceiptHead {
  id: number
  docNo: string
  docStatus: string
  bizDate: string
  supplierId?: number | null
  purchaseOrderId?: number | null
  totalQty?: number | string
  totalAmount?: number | string
  remark?: string | null
}

export interface ReceiptItemRow {
  id: number
  productId: number
  skuCode?: string
  productName?: string
  qty: number | string
  expectQty?: number | string | null
  unitPrice: number | string
  amount: number | string
  poItemId?: number | null
  remark?: string | null
}

export interface ReceiptDetail {
  head: ReceiptHead
  items: ReceiptItemRow[]
}

export interface ReceiptListRow {
  id: number
  docNo: string
  bizDate: string
  docStatus: string
  supplierId?: number | null
  supplierName?: string | null
  purchaseOrderId?: number | null
  totalQty: number | string
  totalAmount: number | string
  itemCount: number
  diffCount: number
  remark?: string | null
}

export interface PriceHistoryRow {
  supplierId: number | null
  supplierName: string | null
  lastPrice: number | string
  lastDate: string
  minPrice: number | string
  buyCount: number
}

export interface PriceCheckResult {
  lastPrice: number | string | null
  compareScope: string
  diffPct: number | string | null
  warn: boolean
  history: PriceHistoryRow[]
}

export interface InTransitRow {
  productId: number
  skuCode?: string
  productName?: string
  inTransitQty: number | string
}

// ---------- 订货单 ----------

export function pagePurchaseOrders(params: {
  current?: number
  size?: number
  poStatus?: string
  supplierId?: number
}): Promise<PageResult<PoVo>> {
  return request.get('/v1/purchase/orders', { params })
}

export function getPurchaseOrder(id: number): Promise<PoVo> {
  return request.get(`/v1/purchase/orders/${id}`)
}

export function getPurchaseOrderItems(id: number): Promise<PoItemRow[]> {
  return request.get(`/v1/purchase/orders/${id}/items`)
}

export function createPurchaseOrder(data: PoCreateReq): Promise<number> {
  return request.post('/v1/purchase/orders', data, opHeaders())
}

export function updatePurchaseOrder(id: number, data: PoCreateReq): Promise<void> {
  return request.put(`/v1/purchase/orders/${id}`, data, opHeaders())
}

export function placePurchaseOrder(id: number): Promise<void> {
  return request.post(`/v1/purchase/orders/${id}/place`, null, opHeaders())
}

export function cancelPurchaseOrder(id: number): Promise<void> {
  return request.post(`/v1/purchase/orders/${id}/cancel`, null, opHeaders())
}

export function closePurchaseOrder(id: number): Promise<void> {
  return request.post(`/v1/purchase/orders/${id}/close`, null, opHeaders())
}

// ---------- 在途 ----------

export function listInTransit(): Promise<InTransitRow[]> {
  return request.get('/v1/purchase/in-transit')
}

export function getInTransit(productId: number): Promise<{
  productId: number
  inTransitQty: number | string
  lines: { poNo: string; expectDate?: string; qtyOrdered: number; qtyReceived: number; overdue: boolean }[]
}> {
  return request.get(`/v1/purchase/in-transit/${productId}`)
}

// ---------- 收货入库 ----------

export function createReceiptFromPo(poId: number, bizDate?: string): Promise<ReceiptDetail> {
  return request.post(`/v1/purchase/orders/${poId}/receipts`, null, { params: { bizDate } })
}

export function createReceipt(data: ReceiptCreateReq): Promise<ReceiptDetail> {
  return request.post('/v1/purchase/receipts', data)
}

export function updateReceipt(docId: number, data: ReceiptCreateReq): Promise<ReceiptDetail> {
  return request.put(`/v1/purchase/receipts/${docId}`, data)
}

export function confirmReceipt(docId: number): Promise<ReceiptDetail> {
  return request.post(`/v1/purchase/receipts/${docId}/confirm`)
}

export function listReceipts(): Promise<ReceiptListRow[]> {
  return request.get('/v1/purchase/receipts')
}

export function getReceipt(docId: number): Promise<ReceiptDetail> {
  return request.get(`/v1/purchase/receipts/${docId}`)
}

// ---------- 价格本 / 比价 ----------

export function getPriceHistory(productId: number, supplierId?: number): Promise<PriceHistoryRow[]> {
  return request.get('/v1/purchase/price-history', { params: { productId, supplierId } })
}

export function priceCheck(productId: number, supplierId?: number, price?: number): Promise<PriceCheckResult> {
  return request.get('/v1/purchase/price-check', { params: { productId, supplierId, price } })
}

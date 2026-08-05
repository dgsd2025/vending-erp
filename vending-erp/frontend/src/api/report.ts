import request from '@/utils/request'

/**
 * 报表 API(M1-6):毛利报表 / 进销存汇总 / 库存查询 / 成本重算。
 */

export interface GrossMarginRow {
  key: number | null
  code: string
  name: string
  salesQty: number
  salesAmt: number
  costAmt: number | null
  grossProfit: number | null
  marginPct: number | null
  hasCost: boolean
  noCostSkuCount: number
}

export interface GrossMarginResp {
  month: string
  dim: 'sku' | 'machine'
  months: string[]
  rows: GrossMarginRow[]
  totalSalesAmt: number
  totalCostAmt: number
  totalGrossProfit: number
  totalMarginPct: number | null
  noCostCount: number
  costedSalesAmt: number
  dataAsOf: string | null
}

export interface InventorySummaryRow {
  productId: number | null
  code: string
  name: string
  openingQty: number
  openingAmt: number | null
  inQty: number
  inAmt: number | null
  outQty: number
  outAmt: number | null
  closingQty: number
  closingAmt: number | null
  hasCost: boolean
}

export interface InventorySummaryResp {
  month: string
  months: string[]
  rows: InventorySummaryRow[]
  total: InventorySummaryRow | null
  dataAsOf: string | null
}

export interface StockMachineCol {
  machineId: number
  machineName: string
}

export interface StockRow {
  productId: number
  code: string
  name: string
  category: string | null
  productStatus: string | null
  warehouseQty: number
  machineQty: Record<string, number>
  totalQty: number
  unitCost: number | null
  amount: number | null
  negative: boolean
}

export interface StockResp {
  machines: StockMachineCol[]
  rows: StockRow[]
  totalAmount: number
  warehouseAmount: number
  machineAmount: number
  negativeCount: number
  dataAsOf: string | null
}

export interface StockLedgerRow {
  id: number
  /** 单据 ID(七律#3:单号可点开通用单据详情抽屉) */
  docId: number
  bizTime: string
  docNo: string
  docType: string
  locationType: string
  machineName: string | null
  changeQty: number
  balanceQty: number
  unitCost: number | null
  amount: number | null
}

export interface RecalcResp {
  saleUpdated: number
  ledgerUpdated: number
  products: number
}

// ---------- 单品/机器详情聚合(M1-9) ----------

export interface TrendPoint {
  label: string
  salesQty: number
  salesAmt: number
  grossProfit: number | null
}

export interface MachineDistRow {
  machineId: number
  machineName: string
  salesQty30: number
  stockQty: number | null
}

export interface PurchaseHistRow {
  /** 单据 ID(七律#3:单号可点开通用单据详情抽屉) */
  docId: number
  bizTime: string
  docNo: string
  docType: string
  supplierName: string | null
  qty: number
  unitCost: number | null
  amount: number | null
}

export interface ProductOverviewResp {
  productId: number
  skuCode: string
  productName: string
  category: string | null
  productStatus: string | null
  unit: string | null
  boxSpec: number | null
  shelfLifeDays: number | null
  refPrice: number | null
  warehouseQty: number
  machineQtyTotal: number
  totalQty: number
  unitCost: number | null
  stockAmount: number | null
  hasCost: boolean
  salesQty30: number
  salesAmt30: number
  grossProfit30: number | null
  marginPct30: number | null
  dailyAvg30: number
  daysOfStock: number | null
  weeklyTrend: TrendPoint[]
  machineDist: MachineDistRow[]
  purchaseHist: PurchaseHistRow[]
  purchaseTotalQty: number
  purchaseTotalAmt: number
  dataAsOf: string | null
}

export interface SlotRow {
  slotId: number
  slotNo: string
  productId: number | null
  productName: string | null
  skuCode: string | null
  capacity: number | null
  /** ⚠️ stale 字段:slot 表现量无写手恒 0,禁止用它出红绿灯(P1-2),展示一律用 estQty */
  currentQty: number | null
  slotStatus: string | null
  /** 该机该 SKU 推算库存(锚点快照+增量;null=无推算数据,格子显「—」不配色) */
  estQty: number | null
  /** 配色分母:同 SKU 全部货道容量合计(单货道=本格容量) */
  estCapacity: number | null
  /** 同 SKU 绑多货道无法拆分 → 格子显 SKU 级合并量 + 「按品合并」徽标 */
  estShared: boolean
}

export interface SkuSalesRow {
  productId: number
  skuCode: string
  productName: string
  salesQty30: number
  salesAmt30: number
}

export interface TransferHistRow {
  docId: number
  docNo: string
  docType: string
  docStatus: string
  bizDate: string
  sourceType: string
  itemCount: number
  totalQty: number
}

export interface MachineOverviewResp {
  machineId: number
  machineCode: string
  machineName: string
  deviceId: string
  location: string | null
  model: string | null
  slotCount: number | null
  machineStatus: string | null
  onlineDate: string | null
  month: string | null
  monthSalesAmt: number
  monthSalesQty: number
  monthGrossProfit: number | null
  monthMarginPct: number | null
  salesSharePct: number | null
  dailyAvgAmt: number
  machineStockQty: number
  capacityTotal: number
  dailyTrend: TrendPoint[]
  topSkus: SkuSalesRow[]
  slots: SlotRow[]
  transferHist: TransferHistRow[]
  dataAsOf: string | null
}

export const reportApi = {
  grossMargin: (month: string | undefined, dim: 'sku' | 'machine') =>
    request.get<never, GrossMarginResp>('/v1/report/gross-margin', { params: { month, dim } }),
  inventorySummary: (month?: string) =>
    request.get<never, InventorySummaryResp>('/v1/report/inventory-summary', { params: { month } }),
  stock: () => request.get<never, StockResp>('/v1/report/stock'),
  productLedger: (productId: number, limit = 100) =>
    request.get<never, StockLedgerRow[]>(`/v1/report/stock/${productId}/ledger`, { params: { limit } }),
  recalc: () => request.post<never, RecalcResp>('/v1/report/cost/recalc'),
  productOverview: (productId: number) =>
    request.get<never, ProductOverviewResp>(`/v1/report/product/${productId}/overview`),
  machineOverview: (machineId: number) =>
    request.get<never, MachineOverviewResp>(`/v1/report/machine/${machineId}/overview`),
}

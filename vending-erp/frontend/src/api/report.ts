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

export const reportApi = {
  grossMargin: (month: string | undefined, dim: 'sku' | 'machine') =>
    request.get<never, GrossMarginResp>('/v1/report/gross-margin', { params: { month, dim } }),
  inventorySummary: (month?: string) =>
    request.get<never, InventorySummaryResp>('/v1/report/inventory-summary', { params: { month } }),
  stock: () => request.get<never, StockResp>('/v1/report/stock'),
  productLedger: (productId: number, limit = 100) =>
    request.get<never, StockLedgerRow[]>(`/v1/report/stock/${productId}/ledger`, { params: { limit } }),
  recalc: () => request.post<never, RecalcResp>('/v1/report/cost/recalc'),
}

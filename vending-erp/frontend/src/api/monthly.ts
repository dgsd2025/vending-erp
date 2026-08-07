import request from '@/utils/request'

/**
 * 月度报表包 API(M4-4,§10.3 七件套 + §10.2 财务工作日历 + 一键导出)。
 * 全部只读聚合;导出走后端生成流(Excel/Word),前端直接下载。后端 R 已解包出 data。
 */

// ---- ① 进销存 ----
export interface InventoryRow {
  productId: number
  code: string
  name: string
  openingQty: number
  openingAmt: number
  inQty: number
  inAmt: number
  outQty: number
  outAmt: number
  closingQty: number
  closingAmt: number
  hasCost: boolean
}
export interface InventorySummary {
  month: string
  months: string[]
  rows: InventoryRow[]
  total: InventoryRow | null
  dataAsOf: string | null
}

// ---- ② 利润表 ----
export interface PlRow {
  key: string
  label: string
  amount: number
  subtotal: boolean
  note: string
}
export interface ProfitResp {
  period: string
  locked: boolean
  rows: PlRow[]
  operatingProfit: number
  settleBanner?: string
}

// ---- ③ 现金流水 ----
export interface CashflowRow {
  name: string
  inflow: number
  outflow: number
  net: number
  count: number
}
export interface AccountBalanceRow {
  accountId: number
  accountName: string
  accountType: string
  openingBalance: number
  monthEndBalance: number
  virtual: boolean
}
export interface CashflowSummary {
  month: string
  byAccount: CashflowRow[]
  byCategory: CashflowRow[]
  monthEndBalances: AccountBalanceRow[]
  totalInflow: number
  totalOutflow: number
  totalNet: number
  monthEndCashTotal: number
}

// ---- ④ 往来 ----
export interface SupplierRow {
  supplierId: number
  supplierName: string
  settleMethod: string
  accountDays: number | null
  openingPayable: number
  purchaseTotal: number
  paymentTotal: number
  balance: number
  prepay: boolean
  overdue: boolean
  overdueDays: number | null
}
export interface PayableSummary {
  suppliers: SupplierRow[]
  payableTotal: number
  prepayTotal: number
  aging: { overdueCount: number; maxOverdueDays: number } | null
  platformPending: { mode: string; pendingBalance: number; overdue: boolean; thresholdDays: number } | null
}

// ---- ⑤ 损耗 ----
export interface LossRow {
  month: string
  reason: string
  itemCount: number
  qty: number
  amount: number
}
export interface LossSummary {
  month: string
  rows: LossRow[]
  totalAmount: number
  totalQty: number
  topReason: string | null
}

// ---- ⑥ 资产 ----
export interface AssetSnapshot {
  inventoryAmount: number
  platformPending: number
  cashTotal: number
  claimReceivable: number
  payableTotal: number
  netAsset: number
  prevPeriod: string | null
  prevNetAsset: number | null
}
export interface SnapshotRow {
  period: string
  inventoryAmount: number
  platformPending: number
  cashTotal: number
  claimReceivable: number
  payableTotal: number
  netAsset: number
}
export interface AssetSummary {
  snapshot: AssetSnapshot | null
  trend: SnapshotRow[]
}

export interface MonthlyPackage {
  month: string
  months: string[]
  dataAsOf: string | null
  locked: boolean
  inventory: InventorySummary
  profit: ProfitResp
  cashflow: CashflowSummary
  payable: PayableSummary
  loss: LossSummary
  asset: AssetSummary
}

// ---- ⑦ 经营分析报告 ----
export interface DataPoint {
  label: string
  value: string
  source: string
}
export interface AnalysisSection {
  key: string
  title: string
  narrative: string
  dataPoints: DataPoint[]
}
export interface AnalysisReport {
  period: string
  months: string[]
  locked: boolean
  aiText: string
  llmCallId: number | null
  cacheHit: boolean
  model: string
  mock: boolean
  sections: AnalysisSection[]
}

// ---- 财务工作日历 ----
export interface CalendarDay {
  day: number
  financeAction: string
  systemAuto: string
  status: 'AUTO_DONE' | 'PENDING_MANUAL' | 'IN_PROGRESS'
  note: string
}
export interface CalendarBoard {
  month: string
  days: CalendarDay[]
  pendingCount: number
}

/** 触发浏览器下载(GET 导出,后端带 Content-Disposition attachment) */
function downloadUrl(path: string) {
  const base = import.meta.env.VITE_API_BASE_URL || '/api'
  const a = document.createElement('a')
  a.href = base + path
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

export const monthlyApi = {
  pkg: (month?: string): Promise<MonthlyPackage> =>
    request.get('/v1/monthly/package', { params: { month } }),
  analysis: (month?: string, force = false): Promise<AnalysisReport> =>
    request.get('/v1/monthly/analysis', { params: { month, force } }),
  calendar: (month?: string): Promise<CalendarBoard> =>
    request.get('/v1/monthly/calendar', { params: { month } }),
  exportExcel: (month?: string) =>
    downloadUrl('/v1/monthly/export/excel' + (month ? `?month=${month}` : '')),
  exportWord: (month?: string) =>
    downloadUrl('/v1/monthly/export/word' + (month ? `?month=${month}` : '')),
}
